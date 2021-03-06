package org.broadinstitute.dsde.workbench.google2

import _root_.org.typelevel.log4cats.StructuredLogger
import cats.Parallel
import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.mtl.Ask
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.api.services.compute.ComputeScopes
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.compute.v1._
import org.broadinstitute.dsde.workbench.RetryConfig
import org.broadinstitute.dsde.workbench.google2.util.RetryPredicates
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{TraceId, WorkbenchEmail}

import scala.collection.JavaConverters._

/**
 * Algebra for Google Compute access.
 */
trait GoogleComputeService[F[_]] {
  def createInstance(project: GoogleProject, zone: ZoneName, instance: Instance)(implicit
    ev: Ask[F, TraceId]
  ): F[Option[Operation]]

  def deleteInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
    ev: Ask[F, TraceId]
  ): F[Option[Operation]]

  /**
   * @param autoDeleteDisks Set of disk device names that should be marked as auto deletable when runtime is deleted
   * @return
   */
  def deleteInstanceWithAutoDeleteDisk(project: GoogleProject,
                                       zone: ZoneName,
                                       instanceName: InstanceName,
                                       autoDeleteDisks: Set[DiskName]
  )(implicit
    ev: Ask[F, TraceId],
    computePollOperation: ComputePollOperation[F]
  ): F[Option[Operation]]

  def detachDisk(project: GoogleProject, zone: ZoneName, instanceName: InstanceName, deviceName: DeviceName)(implicit
    ev: Ask[F, TraceId]
  ): F[Option[Operation]]

  def getInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
    ev: Ask[F, TraceId]
  ): F[Option[Instance]]

  def stopInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
    ev: Ask[F, TraceId]
  ): F[Operation]

  def startInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
    ev: Ask[F, TraceId]
  ): F[Operation]

  def addInstanceMetadata(project: GoogleProject,
                          zone: ZoneName,
                          instanceName: InstanceName,
                          metadata: Map[String, String]
  )(implicit ev: Ask[F, TraceId]): F[Unit] =
    modifyInstanceMetadata(project, zone, instanceName, metadata, Set.empty)

  def removeInstanceMetadata(project: GoogleProject,
                             zone: ZoneName,
                             instanceName: InstanceName,
                             metadataToRemove: Set[String]
  )(implicit ev: Ask[F, TraceId]): F[Unit] =
    modifyInstanceMetadata(project, zone, instanceName, Map.empty, metadataToRemove)

  def modifyInstanceMetadata(project: GoogleProject,
                             zone: ZoneName,
                             instanceName: InstanceName,
                             metadataToAdd: Map[String, String],
                             metadataToRemove: Set[String]
  )(implicit ev: Ask[F, TraceId]): F[Unit]

  def addFirewallRule(project: GoogleProject, firewall: Firewall)(implicit ev: Ask[F, TraceId]): F[Operation]

  def getFirewallRule(project: GoogleProject, firewallRuleName: FirewallRuleName)(implicit
    ev: Ask[F, TraceId]
  ): F[Option[Firewall]]

  def deleteFirewallRule(project: GoogleProject, firewallRuleName: FirewallRuleName)(implicit
    ev: Ask[F, TraceId]
  ): F[Unit]

  def getComputeEngineDefaultServiceAccount(projectNumber: Long): WorkbenchEmail =
    // Service account email format documented in:
    // https://cloud.google.com/compute/docs/access/service-accounts#compute_engine_default_service_account
    WorkbenchEmail(s"$projectNumber-compute@developer.gserviceaccount.com")

  def setMachineType(project: GoogleProject, zone: ZoneName, instanceName: InstanceName, machineType: MachineTypeName)(
    implicit ev: Ask[F, TraceId]
  ): F[Unit]

  def getMachineType(project: GoogleProject, zone: ZoneName, machineTypeName: MachineTypeName)(implicit
    ev: Ask[F, TraceId]
  ): F[Option[MachineType]]

  def getZones(project: GoogleProject, regionName: RegionName)(implicit ev: Ask[F, TraceId]): F[List[Zone]]

  def getNetwork(project: GoogleProject, networkName: NetworkName)(implicit
    ev: Ask[F, TraceId]
  ): F[Option[Network]]

  def createNetwork(project: GoogleProject, network: Network)(implicit ev: Ask[F, TraceId]): F[Operation]

  def getSubnetwork(project: GoogleProject, region: RegionName, subnetwork: SubnetworkName)(implicit
    ev: Ask[F, TraceId]
  ): F[Option[Subnetwork]]

  def createSubnetwork(project: GoogleProject, region: RegionName, subnetwork: Subnetwork)(implicit
    ev: Ask[F, TraceId]
  ): F[Operation]
}

object GoogleComputeService {
  def resource[F[_]: StructuredLogger: Async: Parallel: Timer: ContextShift](
    pathToCredential: String,
    blocker: Blocker,
    blockerBound: Semaphore[F],
    retryConfig: RetryConfig = RetryPredicates.standardGoogleRetryConfig
  ): Resource[F, GoogleComputeService[F]] =
    for {
      credential <- credentialResource(pathToCredential)
      scopedCredential = credential.createScoped(Seq(ComputeScopes.COMPUTE).asJava)
      interpreter <- fromCredential(scopedCredential, blocker, blockerBound, retryConfig)
    } yield interpreter

  def resourceFromUserCredential[F[_]: StructuredLogger: Async: Parallel: Timer: ContextShift](
    pathToCredential: String,
    blocker: Blocker,
    blockerBound: Semaphore[F],
    retryConfig: RetryConfig = RetryPredicates.standardGoogleRetryConfig
  ): Resource[F, GoogleComputeService[F]] =
    for {
      credential <- userCredentials(pathToCredential)
      scopedCredential = credential.createScoped(Seq(ComputeScopes.COMPUTE).asJava)
      interpreter <- fromCredential(scopedCredential, blocker, blockerBound, retryConfig)
    } yield interpreter

  def fromCredential[F[_]: StructuredLogger: Async: Parallel: Timer: ContextShift](
    googleCredentials: GoogleCredentials,
    blocker: Blocker,
    blockerBound: Semaphore[F],
    retryConfig: RetryConfig
  ): Resource[F, GoogleComputeService[F]] = {
    val credentialsProvider = FixedCredentialsProvider.create(googleCredentials)

    val instanceSettings = InstanceSettings
      .newBuilder()
      .setCredentialsProvider(credentialsProvider)
      .build()
    val firewallSettings = FirewallSettings
      .newBuilder()
      .setCredentialsProvider(credentialsProvider)
      .build()
    val zoneSettings = ZoneSettings
      .newBuilder()
      .setCredentialsProvider(credentialsProvider)
      .build()
    val machineTypeSettings = MachineTypeSettings
      .newBuilder()
      .setCredentialsProvider(credentialsProvider)
      .build()
    val networkSettings = NetworkSettings
      .newBuilder()
      .setCredentialsProvider(credentialsProvider)
      .build()
    val subnetworkSettings = SubnetworkSettings
      .newBuilder()
      .setCredentialsProvider(credentialsProvider)
      .build()

    for {
      instanceClient <- backgroundResourceF(InstanceClient.create(instanceSettings))
      firewallClient <- backgroundResourceF(FirewallClient.create(firewallSettings))
      zoneClient <- backgroundResourceF(ZoneClient.create(zoneSettings))
      machineTypeClient <- backgroundResourceF(MachineTypeClient.create(machineTypeSettings))
      networkClient <- backgroundResourceF(NetworkClient.create(networkSettings))
      subnetworkClient <- backgroundResourceF(SubnetworkClient.create(subnetworkSettings))
    } yield new GoogleComputeInterpreter[F](instanceClient,
                                            firewallClient,
                                            zoneClient,
                                            machineTypeClient,
                                            networkClient,
                                            subnetworkClient,
                                            retryConfig,
                                            blocker,
                                            blockerBound
    )
  }
}

final case class InstanceName(value: String) extends AnyVal
final case class FirewallRuleName(value: String) extends AnyVal
final case class MachineTypeName(value: String) extends AnyVal
final case class NetworkName(value: String) extends AnyVal
final case class SubnetworkName(value: String) extends AnyVal
final case class OperationName(value: String) extends AnyVal

final case class RegionName(value: String) extends AnyVal
object RegionName {
  def fromUriString(uri: String): Option[RegionName] = Option(uri).flatMap(_.split("/").lastOption).map(RegionName(_))
}

final case class ZoneName(value: String) extends AnyVal
object ZoneName {
  def fromUriString(uri: String): Option[ZoneName] = Option(uri).flatMap(_.split("/").lastOption).map(ZoneName(_))
}
