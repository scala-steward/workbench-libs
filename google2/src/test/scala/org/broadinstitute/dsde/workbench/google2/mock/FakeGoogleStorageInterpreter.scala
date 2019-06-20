package org.broadinstitute.dsde.workbench.google2
package mock

import java.nio.file.Path

import cats.effect.IO
import com.google.cloud.storage.{Acl, BlobId, BucketInfo}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GoogleProject}
import GoogleStorageInterpreterSpec._
import cats.data.NonEmptyList
import com.google.cloud.Identity
import fs2.Stream
import org.broadinstitute.dsde.workbench.model.TraceId

object FakeGoogleStorageInterpreter extends GoogleStorageService[IO] {
  override def listObjectsWithPrefix(bucketName: GcsBucketName, objectNamePrefix: String, maxPageSize: Long = 1000, traceId: Option[TraceId] = None): fs2.Stream[IO, GcsObjectName] = localStorage.listObjectsWithPrefix(bucketName, objectNamePrefix)

  override def storeObject(bucketName: GcsBucketName, objectName: GcsBlobName, objectContents: Array[Byte], objectType: String, metadata: Map[String, String] = Map.empty, generation: Option[Long], traceId: Option[TraceId] = None): Stream[IO, Unit] = localStorage.storeObject(bucketName, objectName, objectContents, objectType)

  override def setBucketLifecycle(bucketName: GcsBucketName, lifecycleRules: List[BucketInfo.LifecycleRule], traceId: Option[TraceId] = None): Stream[IO, Unit] = Stream.empty

  override def unsafeGetObject(bucketName: GcsBucketName, blobName: GcsBlobName, traceId: Option[TraceId] = None): IO[Option[String]] = localStorage.unsafeGetObject(bucketName, blobName)

  override def getObject(bucketName: GcsBucketName, blobName: GcsBlobName, traceId: Option[TraceId] = None): Stream[IO, Byte] = localStorage.getObject(bucketName, blobName)

  override def downloadObject(blobId: BlobId, path: Path, traceId: Option[TraceId] = None): Stream[IO, Unit] = Stream.eval(IO.unit)

  override def getObjectMetadata(bucketName: GcsBucketName, blobName: GcsBlobName, traceId: Option[TraceId]): Stream[IO, GetMetadataResponse] = Stream.emit(GetMetadataResponse.NotFound).covary[IO]

  override def setObjectMetadata(bucketName: GcsBucketName, objectName: GcsBlobName, metadata: Map[String, String], traceId: Option[TraceId]): Stream[IO, Unit] = Stream.eval(IO.unit)

  override def removeObject(bucketName: GcsBucketName, blobName: GcsBlobName, generation: Option[Long], traceId: Option[TraceId] = None): Stream[IO, RemoveObjectResult] = localStorage.removeObject(bucketName, blobName).as(RemoveObjectResult.Removed)

  @deprecated
  override def createBucket(billingProject: GoogleProject, bucketName: GcsBucketName, acl: Option[NonEmptyList[Acl]] = None, traceId: Option[TraceId] = None): Stream[IO, Unit] = Stream.empty

  override def createBucket(googleProject: GoogleProject, bucketName: GcsBucketName, acl: Option[NonEmptyList[Acl]] = None, labels: Map[String, String] = Map.empty, traceId: Option[TraceId] = None): Stream[IO, Unit] = Stream.empty

  override def setBucketPolicyOnly(bucketName: GcsBucketName, bucketOnlyPolicyEnabled: Boolean, traceId: Option[TraceId] = None): Stream[IO, Unit] = Stream.empty

  override def setIamPolicy(bucketName: GcsBucketName, roles: Map[StorageRole, NonEmptyList[Identity]], traceId: Option[TraceId] = None): Stream[IO, Unit] = Stream.empty
}
