package org.broadinstitute.dsde.workbench.google2

import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.mtl.ApplicativeAsk
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.api.services.compute.ComputeScopes
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.dataproc.v1._
import io.chrisdavenport.log4cats.Logger
import org.broadinstitute.dsde.workbench.RetryConfig
import org.broadinstitute.dsde.workbench.google2.util.RetryPredicates
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}

import scala.collection.JavaConverters._
import scala.language.higherKinds

/**
 * Algebra for Google Dataproc access
 *
 * We follow tagless final pattern similar to https://typelevel.org/cats-tagless/
 */
trait GoogleDataprocService[F[_]] {
  def createCluster(
    project: GoogleProject,
    region: RegionName,
    clusterName: ClusterName,
    createClusterConfig: Option[CreateClusterConfig]
  )(implicit ev: ApplicativeAsk[F, TraceId]): F[CreateClusterResponse]

  def deleteCluster(project: GoogleProject, region: RegionName, clusterName: ClusterName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[DeleteClusterResponse]

  def getCluster(project: GoogleProject, region: RegionName, clusterName: ClusterName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Option[Cluster]]
}

object GoogleDataprocService {
  def resource[F[_]: Logger: Async: Timer: ContextShift](
    pathToCredential: String,
    blocker: Blocker,
    blockerBound: Semaphore[F],
    retryConfig: RetryConfig = RetryPredicates.standardRetryConfig
  ): Resource[F, GoogleDataprocService[F]] =
    for {
      credential <- credentialResource(pathToCredential)
      scopedCredential = credential.createScoped(Seq(ComputeScopes.CLOUD_PLATFORM).asJava)
      interpreter <- fromCredential(scopedCredential, blocker, blockerBound, retryConfig)
    } yield interpreter

  private def fromCredential[F[_]: Logger: Async: Timer: ContextShift](
    googleCredentials: GoogleCredentials,
    blocker: Blocker,
    blockerBound: Semaphore[F],
    retryConfig: RetryConfig
  ): Resource[F, GoogleDataprocService[F]] = {
    val settings = ClusterControllerSettings
      .newBuilder()
      .setCredentialsProvider(FixedCredentialsProvider.create(googleCredentials))
      .build()

    for {
      client <- resourceF(ClusterControllerClient.create(settings))
    } yield new GoogleDataprocInterpreter[F](client, retryConfig, blocker, blockerBound)
  }
}

final case class ClusterName(value: String) extends AnyVal
final case class ClusterErrorDetails(code: Int, message: Option[String])

sealed abstract class CreateClusterResponse
object CreateClusterResponse {
  final case class Success(clusterOperationMetadata: ClusterOperationMetadata) extends CreateClusterResponse
  case object AlreadyExists extends CreateClusterResponse
}

final case class CreateClusterConfig(
  gceClusterConfig: GceClusterConfig,
  nodeInitializationAction: NodeInitializationAction,
  instanceGroupConfig: InstanceGroupConfig,
  stagingBucket: GcsBucketName,
  softwareConfig: SoftwareConfig
) //valid properties are https://cloud.google.com/dataproc/docs/concepts/configuring-clusters/cluster-properties

sealed abstract class DeleteClusterResponse
object DeleteClusterResponse {
  final case class Success(clusterOperationMetadata: ClusterOperationMetadata) extends DeleteClusterResponse
  case object NotFound extends DeleteClusterResponse
}