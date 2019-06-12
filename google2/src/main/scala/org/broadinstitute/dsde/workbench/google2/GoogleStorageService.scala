package org.broadinstitute.dsde.workbench.google2

import java.nio.file.Path

import cats.data.NonEmptyList
import cats.effect._
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.Identity
import com.google.cloud.storage.{Acl, BlobId, StorageOptions}
import com.google.cloud.storage.BucketInfo.LifecycleRule
import fs2.Stream
import io.chrisdavenport.linebacker.Linebacker
import io.chrisdavenport.log4cats.Logger
import org.broadinstitute.dsde.workbench.RetryConfig
import org.broadinstitute.dsde.workbench.google2.GoogleStorageInterpreter.defaultRetryConfig
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GoogleProject}

import scala.language.higherKinds

/**
  * Algebra for Google storage access
  *
  * We follow tagless final pattern similar to https://typelevel.org/cats-tagless/
  */
trait GoogleStorageService[F[_]] {
  /**
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def listObjectsWithPrefix(bucketName: GcsBucketName, objectNamePrefix: String, maxPageSize: Long = 1000, traceId: Option[TraceId] = None): Stream[F, GcsObjectName]

  /**
    * not memory safe. Use listObjectsWithPrefix if you're worried about OOM
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def unsafeListObjectsWithPrefix(bucketName: GcsBucketName, objectNamePrefix: String, maxPageSize: Long = 1000, traceId: Option[TraceId] = None)(implicit sf: Sync[F]): F[List[GcsObjectName]] = listObjectsWithPrefix(bucketName, objectNamePrefix).compile.toList

  /**
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def storeObject(bucketName: GcsBucketName, objectName: GcsBlobName, objectContents: Array[Byte], objectType: String, metadata: Map[String, String] = Map.empty, generation: Option[Long] = None, traceId: Option[TraceId] = None): Stream[F, Unit]

  /**
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def setBucketLifecycle(bucketName: GcsBucketName, lifecycleRules: List[LifecycleRule], traceId: Option[TraceId] = None): Stream[F, Unit]

  /**
    * not memory safe. Use getObject if you're worried about OOM
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def unsafeGetObject(bucketName: GcsBucketName, blobName: GcsBlobName, traceId: Option[TraceId] = None): F[Option[String]]

  /**
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def getObject(bucketName: GcsBucketName, blobName: GcsBlobName, traceId: Option[TraceId] = None): Stream[F, Byte]

  /**
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def downloadObject(blobId: BlobId, path: Path, traceId: Option[TraceId] = None): Stream[F, Unit]

  /**
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def getObjectMetadata(bucketName: GcsBucketName, blobName: GcsBlobName, traceId: Option[TraceId]): Stream[F, GetMetadataResponse]

  /**
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def setObjectMetadata(bucketName: GcsBucketName, blobName: GcsBlobName, metadata: Map[String, String], traceId: Option[TraceId]): Stream[F, Unit]

  /**
    * @return true if deleted; false if not found
    */
  def removeObject(bucketName: GcsBucketName, objectName: GcsBlobName, traceId: Option[TraceId] = None): F[RemoveObjectResult]

  /**
    * @param traceId uuid for tracing a unique call flow in logging
    * Acl is deprecated. Use setIamPolicy if possible
    */
  def createBucket(googleProject: GoogleProject, bucketName: GcsBucketName, acl: Option[NonEmptyList[Acl]] = None, traceId: Option[TraceId] = None): Stream[F, Unit]

  /**
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def setBucketPolicyOnly(bucketName: GcsBucketName, bucketPolicyOnlyEnabled: Boolean, traceId: Option[TraceId] = None): Stream[F, Unit]

  /**
    * @param traceId uuid for tracing a unique call flow in logging
    */
  def setIamPolicy(bucketName: GcsBucketName, roles: Map[StorageRole, NonEmptyList[Identity]], traceId: Option[TraceId] = None): Stream[F, Unit]
}

object GoogleStorageService {
  def resource[F[_]: ContextShift: Timer: Async: Logger: Linebacker](pathToCredentialJson: String, project: Option[GoogleProject] = None, retryConfig: RetryConfig = defaultRetryConfig): Resource[F, GoogleStorageService[F]] = for {
    db <- GoogleStorageInterpreter.storage[F](pathToCredentialJson, Linebacker[F].blockingContext, project)
  } yield GoogleStorageInterpreter[F](db, retryConfig)

  def fromApplicationDefault[F[_]: ContextShift: Timer: Async: Logger: Linebacker](retryConfig: RetryConfig = defaultRetryConfig): Resource[F, GoogleStorageService[F]] = for {
    db <- Resource.liftF(
      Sync[F].delay(
        StorageOptions
          .newBuilder()
          .setCredentials(GoogleCredentials.getApplicationDefault())
          .build()
          .getService
      )
    )
  } yield GoogleStorageInterpreter[F](db, retryConfig)
}

final case class GcsBlobName(value: String) extends AnyVal

sealed trait RemoveObjectResult extends Product with Serializable
object RemoveObjectResult {
  def apply(res: Boolean): RemoveObjectResult = if(res) Removed else NotFound

  final case object Removed extends RemoveObjectResult
  final case object NotFound extends RemoveObjectResult
}

sealed abstract class StorageRole extends Product with Serializable {
  def name: String
}
object StorageRole {
  final case object ObjectCreator extends StorageRole {
    def name: String = "roles/storage.objectCreator"
  }
  final case object ObjectViewer extends StorageRole {
    def name: String = "roles/storage.objectViewer"
  }
  final case object ObjectAdmin extends StorageRole {
    def name: String = "roles/storage.objectAdmin"
  }
  final case object StorageAdmin extends StorageRole {
    def name: String = "roles/storage.admin"
  }
}

final case class Crc32(asString: String) extends AnyVal
sealed abstract class GetMetadataResponse extends Product with Serializable
object GetMetadataResponse {
  final case object NotFound extends GetMetadataResponse
  final case class Metadata(crc32: Crc32, userDefined: Map[String, String], generation: Long) extends GetMetadataResponse
}