package org.broadinstitute.dsde.workbench.google

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.stream.ActorMaterializer
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.{HttpResponseException, InputStreamContent}
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.compute.ComputeScopes
import com.google.api.services.plus.PlusScopes
import com.google.api.services.storage.model.{Bucket, StorageObject}
import com.google.api.services.storage.model.Bucket.Lifecycle
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule.{Action, Condition}
import com.google.api.services.storage.{Storage, StorageScopes}
import org.broadinstitute.dsde.workbench.metrics.GoogleInstrumentedService
import org.broadinstitute.dsde.workbench.util.FutureSupport
import spray.json.{JsArray, JsObject, JsString}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 1/8/18.
  */

class HttpGoogleStorageDAO(serviceAccountClientId: String,
                           pemFile: String,
                           appName: String,
                           override val workbenchMetricBaseName: String)( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext ) extends GoogleStorageDAO with FutureSupport with GoogleUtilities {

  val storageScopes = Seq(StorageScopes.DEVSTORAGE_FULL_CONTROL, ComputeScopes.COMPUTE, PlusScopes.USERINFO_EMAIL, PlusScopes.USERINFO_PROFILE)

  val httpTransport = GoogleNetHttpTransport.newTrustedTransport
  val jsonFactory = JacksonFactory.getDefaultInstance

  implicit val service = GoogleInstrumentedService.Storage

  override def createBucket(billingProjectName: String, bucketName: String): Future[String] = {
    val bucket = new Bucket().setName(bucketName)
    val inserter = getStorage(getBucketServiceAccountCredential).buckets().insert(billingProjectName, bucket)

    retryWhen500orGoogleError(() => {
      executeGoogleRequest(inserter)
      bucketName
    })
  }

  override def storeObject(bucketName: String, objectName: String, objectContents: ByteArrayInputStream, objectType: String = "text/plain"): Future[Unit] = {
    val storageObject = new StorageObject().setName(objectName)
    val media = new InputStreamContent(objectType, objectContents)
    val inserter = getStorage(getBucketServiceAccountCredential).objects().insert(bucketName, storageObject, media)
    inserter.getMediaHttpUploader.setDirectUploadEnabled(true)

    retryWhen500orGoogleError(() => {
      executeGoogleRequest(inserter)
    })
  }

  override def getObject(bucketName: String, objectName: String): Future[Option[ByteArrayOutputStream]] = {
    val getter = getStorage(getBucketServiceAccountCredential).objects().get(bucketName, objectName)
    getter.getMediaHttpDownloader.setDirectDownloadEnabled(true)

    retryWhen500orGoogleError(() => {
      try {
        val objectBytes = new ByteArrayOutputStream()
        getter.executeMediaAndDownloadTo(objectBytes)
        executeGoogleRequest(getter)
        Option(objectBytes)
      } catch {
        case t: HttpResponseException if t.getStatusCode == StatusCodes.NotFound.intValue => None
      }
    })
  }

  //This functionality doesn't exist in the com.google.apis Java library.
  //When we migrate to the com.google.cloud library, we will be able to re-write this to use their implementation
  override def setObjectChangePubSubTrigger(bucketName: String, topicName: String, eventTypes: Seq[String]): Future[Unit] = {
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    import org.broadinstitute.dsde.workbench.google.GoogleRequestJsonSupport._
    import spray.json._
    implicit val materializer = ActorMaterializer()

    val refreshToken = getBucketServiceAccountCredential
    refreshToken.refreshToken()

    val url = s"https://www.googleapis.com/storage/v1/b/$bucketName/notificationConfigs"
    val header = headers.Authorization(OAuth2BearerToken(refreshToken.getAccessToken))

    val entity = JsObject(
      Map(
        "topic" -> JsString(topicName),
        "payload_format" -> JsString("JSON_API_V1"),
        "event_types" -> JsArray(eventTypes.map(JsString(_)).toVector)
      )
    )

    Marshal(entity).to[RequestEntity].flatMap { requestEntity =>
      val request = HttpRequest(
        HttpMethods.POST,
        uri = url,
        headers = List(header),
        entity = requestEntity
      )

      Http().singleRequest(request).map { response =>
        logger.debug(GoogleRequest(HttpMethods.POST.value, url, Option(entity), 0, Option(response.status.intValue), None).toJson(GoogleRequestFormat).compactPrint)
        ()
      }
    }
  }

  override def listObjectsWithPrefix(bucketName: String, objectNamePrefix: String): Future[Seq[StorageObject]] = {
    val getter = getStorage(getBucketServiceAccountCredential).objects().list(bucketName).setPrefix(objectNamePrefix)

    retryWhen500orGoogleError(() => {
      Option(executeGoogleRequest(getter).getItems.asScala).getOrElse(Seq[StorageObject]())
    })
  }

  override def removeObject(bucketName: String, objectName: String): Future[Unit] = {
    val remover = getStorage(getBucketServiceAccountCredential).objects().delete(bucketName, objectName)

    retryWhen500orGoogleError(() => {
      executeGoogleRequest(remover)
    })
  }

  //"Delete" is the only lifecycle type currently supported, so we'll default to it
  override def setBucketLifecycle(bucketName: String, lifecycleAge: Int, lifecycleType: String = "Delete"): Future[Unit] = {
    val lifecycle = new Lifecycle.Rule().setAction(new Action().setType(lifecycleType)).setCondition(new Condition().setAge(lifecycleAge))
    val bucket = new Bucket().setName(bucketName).setLifecycle(new Lifecycle().setRule(List(lifecycle).asJava))
    val updater = getStorage(getBucketServiceAccountCredential).buckets().update(bucketName, bucket)

    retryWhen500orGoogleError(() => {
      executeGoogleRequest(updater)
    })
  }

  private def getStorage(credential: Credential): Storage = {
    new Storage.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
  }

  private def getBucketServiceAccountCredential: Credential = {
    new GoogleCredential.Builder()
      .setTransport(httpTransport)
      .setJsonFactory(jsonFactory)
      .setServiceAccountId(serviceAccountClientId)
      .setServiceAccountScopes(storageScopes.asJava) // grant bucket-creation powers
      .setServiceAccountPrivateKeyFromPemFile(new java.io.File(pemFile))
      .build()
  }

}
