package org.broadinstitute.dsde.workbench.google2
package mock

import java.nio.file.Path

import cats.effect.IO
import com.google.cloud.storage.{Acl, Blob, BlobId, BucketInfo}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GoogleProject}
import GoogleStorageInterpreterSpec._
import cats.data.NonEmptyList
import com.google.cloud.{Identity, Policy}
import fs2.Stream
import org.broadinstitute.dsde.workbench.RetryConfig
import org.broadinstitute.dsde.workbench.model.TraceId

class BaseFakeGoogleStorage extends GoogleStorageService[IO] {
  override def listObjectsWithPrefix(bucketName: GcsBucketName, objectNamePrefix: String, isRecursive: Boolean, maxPageSize: Long = 1000, traceId: Option[TraceId] = None, retryConfig: RetryConfig): fs2.Stream[IO, GcsObjectName] = localStorage.listObjectsWithPrefix(bucketName, objectNamePrefix, isRecursive)

  override def listBlobsWithPrefix(bucketName: GcsBucketName, objectNamePrefix: String, isRecursive: Boolean, maxPageSize: Long = 1000, traceId: Option[TraceId] = None, retryConfig: RetryConfig): fs2.Stream[IO, Blob] = localStorage.listBlobsWithPrefix(bucketName, objectNamePrefix, isRecursive)

  override def setBucketLifecycle(bucketName: GcsBucketName, lifecycleRules: List[BucketInfo.LifecycleRule], traceId: Option[TraceId] = None, retryConfig: RetryConfig): Stream[IO, Unit] = Stream.empty

  override def unsafeGetBlobBody(bucketName: GcsBucketName, blobName: GcsBlobName, traceId: Option[TraceId] = None, retryConfig: RetryConfig): IO[Option[String]] = localStorage.unsafeGetBlobBody(bucketName, blobName)

  override def getBlobBody(bucketName: GcsBucketName, blobName: GcsBlobName, traceId: Option[TraceId] = None, retryConfig: RetryConfig): Stream[IO, Byte] = localStorage.getBlobBody(bucketName, blobName, traceId)

  override def getBlob(bucketName: GcsBucketName, blobName: GcsBlobName, traceId: Option[TraceId] = None, retryConfig: RetryConfig): Stream[IO, Blob] = localStorage.getBlob(bucketName, blobName, traceId)

  override def downloadObject(blobId: BlobId, path: Path, traceId: Option[TraceId] = None, retryConfig: RetryConfig): Stream[IO, Unit] = Stream.eval(IO.unit)

  override def getObjectMetadata(bucketName: GcsBucketName, blobName: GcsBlobName, traceId: Option[TraceId], retryConfig: RetryConfig): Stream[IO, GetMetadataResponse] = localStorage.getObjectMetadata(bucketName, blobName, traceId)

  override def setObjectMetadata(bucketName: GcsBucketName, objectName: GcsBlobName, metadata: Map[String, String], traceId: Option[TraceId], retryConfig: RetryConfig): Stream[IO, Unit] = Stream.eval(IO.unit)

  override def removeObject(bucketName: GcsBucketName, blobName: GcsBlobName, generation: Option[Long], traceId: Option[TraceId] = None, retryConfig: RetryConfig): Stream[IO, RemoveObjectResult] = localStorage.removeObject(bucketName, blobName).as(RemoveObjectResult.Removed)

  override def insertBucket(googleProject: GoogleProject, bucketName: GcsBucketName, bucketRegion: Option[String] = None, acl: Option[NonEmptyList[Acl]] = None, labels: Map[String, String] = Map.empty, traceId: Option[TraceId] = None, retryConfig: RetryConfig): Stream[IO, Unit] = Stream.empty

  override def setBucketPolicyOnly(bucketName: GcsBucketName, bucketOnlyPolicyEnabled: Boolean, traceId: Option[TraceId] = None, retryConfig: RetryConfig): Stream[IO, Unit] = Stream.empty

  override def setBucketLabels(bucketName: GcsBucketName, labels: Map[String, String], traceId: Option[TraceId] = None, retryConfig: RetryConfig): Stream[IO, Unit] = Stream.empty

  override def setIamPolicy(bucketName: GcsBucketName, roles: Map[StorageRole, NonEmptyList[Identity]], traceId: Option[TraceId] = None, retryConfig: RetryConfig): Stream[IO, Unit] = Stream.empty

  override def createBlob(bucketName: GcsBucketName, objectName: GcsBlobName, objectContents: Array[Byte], objectType: String, metadata: Map[String, String], generation: Option[Long], traceId: Option[TraceId], retryConfig: RetryConfig): Stream[IO, Blob] = localStorage.createBlob(bucketName, objectName, objectContents, objectType, metadata, generation, traceId)

  override def getIamPolicy(bucketName: GcsBucketName, traceId: Option[TraceId], retryConfig: RetryConfig): Stream[IO, Policy] = localStorage.getIamPolicy(bucketName, traceId)
}

object FakeGoogleStorageInterpreter extends BaseFakeGoogleStorage