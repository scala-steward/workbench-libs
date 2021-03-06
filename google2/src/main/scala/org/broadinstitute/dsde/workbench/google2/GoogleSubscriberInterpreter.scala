package org.broadinstitute.dsde.workbench.google2

import cats.effect._
import cats.effect.implicits._
import cats.syntax.all._
import com.google.api.core.ApiService
import com.google.api.gax.batching.FlowControlSettings
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.api.gax.rpc.AlreadyExistsException
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.pubsub.v1._
import com.google.common.util.concurrent.MoreExecutors
import com.google.protobuf.Timestamp
import com.google.pubsub.v1.{PubsubMessage, _}
import fs2.Stream
import fs2.concurrent.Queue
import org.typelevel.log4cats.{Logger, StructuredLogger}
import io.circe.Decoder
import io.circe.parser._
import org.broadinstitute.dsde.workbench.model.TraceId

import scala.concurrent.duration.FiniteDuration

private[google2] class GoogleSubscriberInterpreter[F[_]: Timer: ContextShift, MessageType](
  subscriber: Subscriber,
  queue: fs2.concurrent.Queue[F, Event[MessageType]]
)(implicit F: Async[F])
    extends GoogleSubscriber[F, MessageType] {
  val messages: Stream[F, Event[MessageType]] = queue.dequeue

  def start: F[Unit] = F.async[Unit] { callback =>
    subscriber.addListener(
      new ApiService.Listener() {
        override def failed(from: ApiService.State, failure: Throwable): Unit =
          callback(Left(failure))
        override def terminated(from: ApiService.State): Unit =
          callback(Right(()))
      },
      MoreExecutors.directExecutor()
    )
    subscriber.startAsync()
  }

  def stop: F[Unit] =
    F.async[Unit] { callback =>
      subscriber.addListener(
        new ApiService.Listener() {
          override def failed(from: ApiService.State, failure: Throwable): Unit =
            callback(Left(failure))
          override def terminated(from: ApiService.State): Unit =
            callback(Right(()))
        },
        MoreExecutors.directExecutor()
      )
      subscriber.stopAsync()
    }
}

object GoogleSubscriberInterpreter {
  def apply[F[_]: Async: Timer: ContextShift, MessageType](
    subscriber: Subscriber,
    queue: fs2.concurrent.Queue[F, Event[MessageType]]
  ): GoogleSubscriberInterpreter[F, MessageType] = new GoogleSubscriberInterpreter[F, MessageType](subscriber, queue)

  private[google2] def receiver[F[_], MessageType: Decoder](
    queue: fs2.concurrent.Queue[F, Event[MessageType]]
  )(implicit logger: StructuredLogger[F], F: Effect[F]): MessageReceiver = new MessageReceiver() {
    override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit = {
      val parseEvent = for {
        isJson <- F.fromEither(parse(message.getData.toStringUtf8)).attempt
        msg <- isJson match {
          case Left(_) =>
            F.raiseError[MessageType](new Exception(s"${message.getData.toStringUtf8} is not a valid Json"))
          case Right(json) =>
            F.fromEither(json.as[MessageType])
        }
        traceId = Option(message.getAttributesMap.get("traceId")).map(s => TraceId(s))
      } yield Event(msg, traceId, message.getPublishTime, consumer)

      val result = for {
        res <- parseEvent.attempt
        _ <- res match {
          case Right(event) =>
            val loggingContext = Map("traceId" -> event.traceId.map(_.asString).getOrElse("None"))

            for {
              r <- queue.enqueue1(event).attempt

              _ <- r match {
                case Left(e) =>
                  logger.info(loggingContext)(s"Subscriber fail to enqueue $message due to $e") >> F.delay(
                    consumer.nack()
                  ) //pubsub will resend the message up to ackDeadlineSeconds (this is configed during subscription creation)
                case Right(_) =>
                  logger.info(loggingContext)(s"Subscriber Successfully received $message.")
              }
            } yield ()
          case Left(e) =>
            logger
              .info(s"Subscriber fail to decode message ${message} due to ${e}. Going to ack the message") >> F
              .delay(consumer.ack())
        }
      } yield ()

      result.toIO.unsafeRunSync
    }
  }

  private[google2] def stringReceiver[F[_]: Effect](queue: fs2.concurrent.Queue[F, Event[String]]): MessageReceiver =
    new MessageReceiver() {
      override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit = {
        val enqueueAction = queue.enqueue1(
          Event(message.getData.toStringUtf8,
                Option(message.getAttributesMap.get("traceId")).map(s => TraceId(s)),
                message.getPublishTime,
                consumer
          )
        )
        enqueueAction.toIO.unsafeRunSync
      }
    }

  def subscriber[F[_]: Effect: StructuredLogger, MessageType: Decoder](
    subscriberConfig: SubscriberConfig,
    queue: fs2.concurrent.Queue[F, Event[MessageType]]
  ): Resource[F, Subscriber] = {
    val subscription = subscriberConfig.subscriptionName.getOrElse(
      ProjectSubscriptionName.of(subscriberConfig.topicName.getProject, subscriberConfig.topicName.getTopic)
    )

    for {
      credential <- credentialResource(subscriberConfig.pathToCredentialJson)
      subscriptionAdminClient <- subscriptionAdminClientResource(credential)
      _ <- createSubscription(subscriberConfig, subscription, subscriptionAdminClient)
      flowControlSettings = subscriberConfig.flowControlSettingsConfig.map(config =>
        FlowControlSettings.newBuilder
          .setMaxOutstandingElementCount(config.maxOutstandingElementCount)
          .setMaxOutstandingRequestBytes(config.maxOutstandingRequestBytes)
          .build
      )
      sub <- subscriberResource(queue, subscription, credential, flowControlSettings)
    } yield sub
  }

  def stringSubscriber[F[_]: Effect: StructuredLogger](
    subscriberConfig: SubscriberConfig,
    queue: fs2.concurrent.Queue[F, Event[String]]
  ): Resource[F, Subscriber] = {
    val subscription = subscriberConfig.subscriptionName.getOrElse(
      ProjectSubscriptionName.of(subscriberConfig.topicName.getProject, subscriberConfig.topicName.getTopic)
    )

    for {
      credential <- credentialResource(subscriberConfig.pathToCredentialJson)
      subscriptionAdminClient <- subscriptionAdminClientResource(credential)
      _ <- createSubscription(subscriberConfig, subscription, subscriptionAdminClient)
      flowControlSettings = subscriberConfig.flowControlSettingsConfig.map(config =>
        FlowControlSettings.newBuilder
          .setMaxOutstandingElementCount(config.maxOutstandingElementCount)
          .setMaxOutstandingRequestBytes(config.maxOutstandingRequestBytes)
          .build
      )
      sub <- stringSubscriberResource(queue, subscription, credential, flowControlSettings)
    } yield sub
  }

  private def subscriberResource[MessageType: Decoder, F[_]: Effect: StructuredLogger](
    queue: Queue[F, Event[MessageType]],
    subscription: ProjectSubscriptionName,
    credential: ServiceAccountCredentials,
    flowControlSettings: Option[FlowControlSettings]
  ): Resource[F, Subscriber] = {
    val subscriber = for {
      builder <- Sync[F].delay(
        Subscriber
          .newBuilder(subscription, receiver(queue))
          .setCredentialsProvider(FixedCredentialsProvider.create(credential))
      )
      builderWithFlowControlSetting <- flowControlSettings.traverse { fcs =>
        Sync[F].delay(builder.setFlowControlSettings(fcs))
      }
    } yield builderWithFlowControlSetting.getOrElse(builder).build()

    Resource.make(subscriber)(s => Sync[F].delay(s.stopAsync()))
  }

  private def stringSubscriberResource[F[_]: Effect](
    queue: Queue[F, Event[String]],
    subscription: ProjectSubscriptionName,
    credential: ServiceAccountCredentials,
    flowControlSettings: Option[FlowControlSettings]
  ): Resource[F, Subscriber] = {
    val subscriber = for {
      builder <- Sync[F].delay(
        Subscriber
          .newBuilder(subscription, stringReceiver(queue))
          .setCredentialsProvider(FixedCredentialsProvider.create(credential))
      )
      builderWithFlowControlSetting <- flowControlSettings.traverse { fcs =>
        Sync[F].delay(builder.setFlowControlSettings(fcs))
      }
    } yield builderWithFlowControlSetting.getOrElse(builder).build()

    Resource.make(subscriber)(s => Sync[F].delay(s.stopAsync()))
  }

  private def createSubscription[F[_]: Effect: Logger](
    subscriberConfig: SubscriberConfig,
    subscription: ProjectSubscriptionName,
    subscriptionAdminClient: SubscriptionAdminClient
  ): Resource[F, Unit] = {
    val initialSub = Subscription
      .newBuilder()
      .setName(subscription.toString)
      .setTopic(subscriberConfig.topicName.toString)
      .setPushConfig(PushConfig.getDefaultInstance)
      .setAckDeadlineSeconds(subscriberConfig.ackDeadLine.toSeconds.toInt)

    val subWithDeadLetterPolicy = subscriberConfig.deadLetterPolicy.fold(initialSub) { deadLetterPolicy =>
      initialSub
        .setDeadLetterPolicy(
          DeadLetterPolicy
            .newBuilder()
            .setDeadLetterTopic(deadLetterPolicy.topicName.toString)
            .setMaxDeliveryAttempts(deadLetterPolicy.maxRetries.value)
            .build()
        )
    }

    val sub = subscriberConfig.filter.fold(subWithDeadLetterPolicy.build()) { ft =>
      subWithDeadLetterPolicy.setFilter(ft).build()
    }

    Resource.eval(
      Async[F]
        .delay(
          subscriptionAdminClient.createSubscription(sub)
        )
        .void
        .recover { case _: AlreadyExistsException =>
          Logger[F].info(s"subscription ${subscription} already exists")
        }
    )
  }

  private def subscriptionAdminClientResource[F[_]: Effect: Logger](credential: ServiceAccountCredentials) =
    Resource.make[F, SubscriptionAdminClient](
      Async[F].delay(
        SubscriptionAdminClient.create(
          SubscriptionAdminSettings
            .newBuilder()
            .setCredentialsProvider(FixedCredentialsProvider.create(credential))
            .build()
        )
      )
    )(client => Async[F].delay(client.shutdown()))
}

final case class FlowControlSettingsConfig(maxOutstandingElementCount: Long, maxOutstandingRequestBytes: Long)
final case class SubscriberConfig(
  pathToCredentialJson: String,
  topicName: TopicName,
  subscriptionName: Option[ProjectSubscriptionName], //it'll have the same name as topic if this is None
  ackDeadLine: FiniteDuration,
  deadLetterPolicy: Option[SubscriberDeadLetterPolicy],
  flowControlSettingsConfig: Option[FlowControlSettingsConfig],
  filter: Option[String]
)
final case class MaxRetries(value: Int) extends AnyVal
final case class SubscriberDeadLetterPolicy(topicName: TopicName, maxRetries: MaxRetries)

final case class Event[A](msg: A, traceId: Option[TraceId] = None, publishedTime: Timestamp, consumer: AckReplyConsumer)
