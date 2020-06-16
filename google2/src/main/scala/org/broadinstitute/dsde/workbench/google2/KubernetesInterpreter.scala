package org.broadinstitute.dsde.workbench.google2

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

import cats.effect.concurrent.Semaphore
import cats.effect.{Async, Blocker, ContextShift, Effect, Timer}
import io.chrisdavenport.log4cats.StructuredLogger
import org.broadinstitute.dsde.workbench.RetryConfig
import cats.implicits._
import cats.effect.implicits._
import cats.mtl.ApplicativeAsk
import com.google.auth.oauth2.{AccessToken, GoogleCredentials}
import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.google.container.v1.Cluster
import io.kubernetes.client.{ApiClient, ApiException}
import io.kubernetes.client.apis.CoreV1Api
import io.kubernetes.client.util.Config
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
import org.broadinstitute.dsde.workbench.google2.KubernetesModels._
import org.broadinstitute.dsde.workbench.model.TraceId
import JavaSerializableSyntax._
import JavaSerializableInstances._

// This uses a kubernetes client library to make calls to the kubernetes API. The client library is autogenerated from the kubernetes API.
// It is highly recommended to use the kubernetes API docs here https://kubernetes.io/docs/reference/generated/kubernetes-api as opposed to the client library docs

class KubernetesInterpreter[F[_]: Async: StructuredLogger: Effect: Timer: ContextShift](
  credentials: GoogleCredentials,
  gkeService: GKEService[F],
  blocker: Blocker,
  blockerBound: Semaphore[F],
  retryConfig: RetryConfig
)(implicit ev: ApplicativeAsk[F, TraceId])
    extends KubernetesService[F] {

  //We cache a kubernetes client for each cluster
  val cache = CacheBuilder
    .newBuilder()
    // We expect calls to be batched, such as when a user's environment within a cluster is created/deleted/stopped.
    // This may need configuration
    .expireAfterWrite(2, TimeUnit.HOURS)
    .build(
      new CacheLoader[KubernetesClusterId, ApiClient] {
        def load(clusterId: KubernetesClusterId): ApiClient = {
          val res = for {
            _ <- StructuredLogger[F]
              .info(s"Determined that there is no cached client for kubernetes cluster ${clusterId}. Creating a client")
            clusterOpt <- gkeService.getCluster(clusterId)
            cluster <- Async[F].fromEither(
              clusterOpt.toRight(
                KubernetesClusterNotFoundException(
                  s"Could not create client for cluster ${clusterId} because it does not exist in google"
                )
              )
            )
            token <- getToken
            client <- createClient(
              cluster,
              token
            )
          } yield client

          res.toIO.unsafeRunSync()
        }
      }
    )

  // https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.12/#podspec-v1-core
  override def createPod(clusterId: KubernetesClusterId, pod: KubernetesPod, namespace: KubernetesNamespace): F[Unit] =
    blockingClientProvider(clusterId, { kubernetesClient =>
      Async[F].delay(
        kubernetesClient.createNamespacedPod(namespace.name.value, pod.getJavaSerialization, null, null, null)
      )
    })

  //why we use a service over a deployment https://matthewpalmer.net/kubernetes-app-developer/articles/service-kubernetes-example-tutorial.html
  //services can be applied to pods/containers, while deployments are for pre-creating pods/containers
  override def createService(clusterId: KubernetesClusterId,
                             service: KubernetesServiceKind,
                             namespace: KubernetesNamespace): F[Unit] =
    blockingClientProvider(
      clusterId, { kubernetesClient =>
        Async[F].delay(
          kubernetesClient.createNamespacedService(namespace.name.value, service.getJavaSerialization, null, null, null)
        )
      }
    )

  override def createNamespace(clusterId: KubernetesClusterId, namespace: KubernetesNamespace): F[Unit] =
    blockingClientProvider(clusterId, { kubernetesClient =>
      Async[F].delay(kubernetesClient.createNamespace(namespace.getJavaSerialization, null, null, null))
    })

  //DO NOT QUERY THE CACHE DIRECTLY
  //There is a wrapper method that is necessary to ensure the token is refreshed
  //we never make the entry stale, because we always need to refresh the token (see comment above getToken)
  //if we did stale the entry we would have to unnecessarily re-do the google call
  private def getClient(clusterId: KubernetesClusterId): F[CoreV1Api] =
    for {
      client <- Async[F].delay(cache.get(clusterId))
      token <- getToken
      _ <- Async[F].delay(client.setApiKey(token.getTokenValue))
    } yield new CoreV1Api(client)

  //we always update the token, even for existing clients, so we don't have to maintain a reference to the last time each client was updated
  //unfortunately, the kubernetes client does not implement a gcp authenticator, so we must do this ourselves.
  //See this for details https://github.com/kubernetes-client/java/issues/290
  private def getToken(): F[AccessToken] =
    for {
      _ <- Async[F].delay(credentials.refreshIfExpired())
    } yield credentials.getAccessToken

  //The underlying http client for ApiClient claims that it releases idle threads and that shutdown is not necessary
  //Here is a guide on how to proactively release resource if this proves to be problematic https://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/#shutdown-isnt-necessary
  private def createClient(cluster: Cluster, token: AccessToken): F[ApiClient] = {
    val endpoint = KubernetesApiServerIp(cluster.getEndpoint)
    val cert = KubernetesClusterCaCert(cluster.getMasterAuth.getClusterCaCertificate)

    for {
      cert <- Async[F].fromEither(cert.base64Cert)
      certResource = autoClosableResourceF(new ByteArrayInputStream(cert))
      apiClient <- certResource.use { certStream =>
        Async[F].delay(
          Config
            .fromToken(
              endpoint.url,
              token.getTokenValue
            )
            .setSslCaCert(certStream)
        )
      }
    } yield (apiClient)
  }

  //TODO: retry once we know what kubernetes codes are applicable
  private def blockingClientProvider[A](clusterId: KubernetesClusterId, fa: CoreV1Api => F[A]): F[A] =
    blockerBound.withPermit(
      blocker
        .blockOn(
          for {
            kubernetesClient <- getClient(clusterId)
            clientCallResult <- fa(kubernetesClient).onError { //we aren't handling any errors here, they will be bubbled up, but we want to print a more helpful message that is otherwise obfuscated
              case e: ApiException =>  Async[F].delay(StructuredLogger[F].info(e.getResponseBody()))
            }
          } yield clientCallResult
        )

    )

}