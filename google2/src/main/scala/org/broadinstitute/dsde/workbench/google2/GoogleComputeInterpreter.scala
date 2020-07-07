package org.broadinstitute.dsde.workbench
package google2

import _root_.io.chrisdavenport.log4cats.StructuredLogger
import cats.Parallel
import cats.effect.concurrent.Semaphore
import cats.effect.{Async, Blocker, ContextShift, Timer}
import cats.implicits._
import cats.mtl.ApplicativeAsk
import com.google.cloud.compute.v1._
import org.broadinstitute.dsde.workbench.google2.util.RetryPredicates._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{TraceId, WorkbenchException}

import scala.collection.JavaConverters._
import scala.concurrent.TimeoutException
import scala.concurrent.duration._

private[google2] class GoogleComputeInterpreter[F[_]: Async: Parallel: StructuredLogger: Timer: ContextShift](
  instanceClient: InstanceClient,
  firewallClient: FirewallClient,
  zoneClient: ZoneClient,
  machineTypeClient: MachineTypeClient,
  networkClient: NetworkClient,
  subnetworkClient: SubnetworkClient,
  retryConfig: RetryConfig,
  blocker: Blocker,
  blockerBound: Semaphore[F]
) extends GoogleComputeService[F] {

  override def createInstance(project: GoogleProject, zone: ZoneName, instance: Instance)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Operation] = {
    val projectZone = ProjectZoneName.of(project.value, zone.value)
    retryF(
      Async[F].delay(instanceClient.insertInstance(projectZone, instance)),
      s"com.google.cloud.compute.v1.InstanceClient.insertInstance(${projectZone.toString}, ${instance.getName})"
    )
  }

  override def deleteInstanceWithAutoDeleteDisk(project: GoogleProject,
                                                zone: ZoneName,
                                                instanceName: InstanceName,
                                                autoDeleteDisks: Set[DiskName])(
    implicit ev: ApplicativeAsk[F, TraceId],
    computePollOperation: ComputePollOperation[F]
  ): F[Operation] = {
    val projectZoneInstanceName = ProjectZoneInstanceName.of(instanceName.value, project.value, zone.value)

    for {
      traceId <- ev.ask
      _ <- autoDeleteDisks.toList.parTraverse { diskName =>
        for {
          operation <- withLogging(
            Async[F].delay(instanceClient.setDiskAutoDeleteInstance(projectZoneInstanceName, true, diskName.value)),
            Some(traceId),
            s"com.google.cloud.compute.v1.InstanceClient.setDiskAutoDeleteInstance(${projectZoneInstanceName.toString}, true, ${diskName.value})"
          )
          _ <- computePollOperation.pollZoneOperation(project,
                                                      zone,
                                                      OperationName(operation.getName),
                                                      1 seconds,
                                                      5,
                                                      None)(
            Async[F].unit,
            Async[F].raiseError[Unit](
              new TimeoutException(s"Fail to setDiskAutoDeleteInstance for ${diskName} in a timely manner")
            ),
            Async[F].unit
          )
        } yield ()
      }
      deleteOp <- retryF(
        Async[F].delay(instanceClient.deleteInstance(projectZoneInstanceName)),
        s"com.google.cloud.compute.v1.InstanceClient.deleteInstance(${projectZoneInstanceName.toString})"
      )
    } yield deleteOp
  }

  override def deleteInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Operation] = {
    val projectZoneInstanceName = ProjectZoneInstanceName.of(instanceName.value, project.value, zone.value)

    retryF(
      Async[F].delay(instanceClient.deleteInstance(projectZoneInstanceName)),
      s"com.google.cloud.compute.v1.InstanceClient.deleteInstance(${projectZoneInstanceName.toString})"
    )
  }

  override def detachDisk(project: GoogleProject, zone: ZoneName, instanceName: InstanceName, deviceName: DeviceName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Operation] = {
    val projectZoneInstanceName = ProjectZoneInstanceName.of(instanceName.value, project.value, zone.value)

    for {
      traceId <- ev.ask
      op <- withLogging(
        Async[F].delay(instanceClient.detachDiskInstance(projectZoneInstanceName, deviceName.asString)),
        Some(traceId),
        s"com.google.cloud.compute.v1.InstanceClient.detachDiskInstance(${projectZoneInstanceName.toString}, ${deviceName.asString})"
      )
    } yield op
  }

  override def getInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Option[Instance]] = {
    val projectZoneInstanceName = ProjectZoneInstanceName.of(instanceName.value, project.value, zone.value)
    retryF(
      recoverF(
        Async[F].delay(instanceClient.getInstance(projectZoneInstanceName)),
        whenStatusCode(404)
      ),
      s"com.google.cloud.compute.v1.InstanceClient.getInstance(${projectZoneInstanceName.toString})"
    )
  }

  override def stopInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Operation] = {
    val projectZoneInstanceName = ProjectZoneInstanceName.of(instanceName.value, project.value, zone.value)
    retryF(
      Async[F].delay(instanceClient.stopInstance(projectZoneInstanceName)),
      s"com.google.cloud.compute.v1.InstanceClient.stopInstance(${projectZoneInstanceName.toString})"
    )
  }

  override def startInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Operation] = {
    val projectZoneInstanceName = ProjectZoneInstanceName.of(instanceName.value, project.value, zone.value)
    retryF(
      Async[F].delay(instanceClient.startInstance(projectZoneInstanceName)),
      s"com.google.cloud.compute.v1.InstanceClient.startInstance(${projectZoneInstanceName.toString})"
    )
  }

  override def modifyInstanceMetadata(
    project: GoogleProject,
    zone: ZoneName,
    instanceName: InstanceName,
    metadataToAdd: Map[String, String],
    metadataToRemove: Set[String]
  )(implicit ev: ApplicativeAsk[F, TraceId]): F[Unit] = {
    val projectZoneInstanceName = ProjectZoneInstanceName.of(instanceName.value, project.value, zone.value)
    val readAndUpdate = for {
      instanceOpt <- recoverF(Async[F].delay(instanceClient.getInstance(projectZoneInstanceName)), whenStatusCode(404))
      instance <- Async[F].fromEither(
        instanceOpt.toRight(new WorkbenchException(s"Instance not found: ${projectZoneInstanceName.toString}"))
      )
      curMetadataOpt = Option(instance.getMetadata)

      fingerprint = curMetadataOpt.map(_.getFingerprint).orNull
      curItems = curMetadataOpt.flatMap(m => Option(m.getItemsList)).map(_.asScala).getOrElse(List.empty)
      filteredItems = curItems.filterNot { i =>
        metadataToRemove.contains(i.getKey) || metadataToAdd.contains(i.getKey)
      }
      newItems = filteredItems ++ metadataToAdd.toList.map {
        case (k, v) =>
          Items.newBuilder().setKey(k).setValue(v).build()
      }
      // Only make google call if there is a change
      _ <- if (!newItems.equals(curItems)) {
        Async[F]
          .delay(
            instanceClient.setMetadataInstance(projectZoneInstanceName,
                                               Metadata
                                                 .newBuilder()
                                                 .setFingerprint(fingerprint)
                                                 .addAllItems(newItems.asJava)
                                                 .build)
          )
          .void
      } else Async[F].unit
    } yield ()

    // block and retry the read-modify-write as an atomic unit
    retryF(
      readAndUpdate,
      s"com.google.cloud.compute.v1.InstanceClient.setMetadataInstance(${projectZoneInstanceName.toString})"
    )
  }

  override def addFirewallRule(project: GoogleProject,
                               firewall: Firewall)(implicit ev: ApplicativeAsk[F, TraceId]): F[Operation] =
    retryF(
      Async[F].delay(firewallClient.insertFirewall(project.value, firewall)),
      s"com.google.cloud.compute.v1.FirewallClient.insertFirewall(${project.value}, ${firewall.getName})"
    )

  override def getFirewallRule(project: GoogleProject, firewallRuleName: FirewallRuleName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Option[Firewall]] = {
    val projectFirewallRuleName = ProjectGlobalFirewallName.of(firewallRuleName.value, project.value)
    retryF(
      recoverF(
        Async[F].delay(firewallClient.getFirewall(projectFirewallRuleName)),
        whenStatusCode(404)
      ),
      s"com.google.cloud.compute.v1.FirewallClient.insertFirewall(${project.value}, ${firewallRuleName.value})"
    )
  }

  override def deleteFirewallRule(project: GoogleProject, firewallRuleName: FirewallRuleName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Unit] = {
    val request =
      ProjectGlobalFirewallName.newBuilder().setProject(project.value).setFirewall(firewallRuleName.value).build
    retryF(
      recoverF(Async[F].delay(firewallClient.deleteFirewall(request)), whenStatusCode(404)),
      s"com.google.cloud.compute.v1.FirewallClient.deleteFirewall(${project.value}, ${firewallRuleName.value})"
    ).void
  }

  override def setMachineType(project: GoogleProject,
                              zone: ZoneName,
                              instanceName: InstanceName,
                              machineTypeName: MachineTypeName)(implicit ev: ApplicativeAsk[F, TraceId]): F[Unit] = {
    val projectZoneInstanceName = ProjectZoneInstanceName.of(instanceName.value, project.value, zone.value)
    val request =
      InstancesSetMachineTypeRequest.newBuilder().setMachineType(buildMachineTypeUri(zone, machineTypeName)).build()
    retryF(
      Async[F].delay(instanceClient.setMachineTypeInstance(projectZoneInstanceName, request)),
      s"com.google.cloud.compute.v1.InstanceClient.setMachineTypeInstance(${projectZoneInstanceName.toString}, ${machineTypeName.value})"
    )
  }

  override def getZones(project: GoogleProject,
                        regionName: RegionName)(implicit ev: ApplicativeAsk[F, TraceId]): F[List[Zone]] = {
    val request = ListZonesHttpRequest
      .newBuilder()
      .setProject(project.value)
      .setFilter(s"region eq ${buildRegionUri(project, regionName)}")
      .build()

    retryF(
      Async[F].delay(zoneClient.listZones(request)),
      s"com.google.cloud.compute.v1.ZoneClient.listZones(${project.value}, ${regionName.value})"
    ).map(_.iterateAll.asScala.toList)
  }

  override def getMachineType(project: GoogleProject, zone: ZoneName, machineTypeName: MachineTypeName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Option[MachineType]] = {
    val projectZoneMachineTypeName = ProjectZoneMachineTypeName.of(machineTypeName.value, project.value, zone.value)
    retryF(
      recoverF(Async[F].delay(machineTypeClient.getMachineType(projectZoneMachineTypeName)), whenStatusCode(404)),
      s"com.google.cloud.compute.v1.MachineTypeClient.getMachineType(${projectZoneMachineTypeName.toString})"
    )
  }

  override def getNetwork(project: GoogleProject,
                          networkName: NetworkName)(implicit ev: ApplicativeAsk[F, TraceId]): F[Option[Network]] = {
    val projectNetworkName =
      ProjectGlobalNetworkName.newBuilder().setProject(project.value).setNetwork(networkName.value).build
    retryF(
      recoverF(Async[F].delay(networkClient.getNetwork(projectNetworkName)), whenStatusCode(404)),
      s"com.google.cloud.compute.v1.NetworkClient.getNetwork(${projectNetworkName.toString})"
    )
  }

  override def createNetwork(project: GoogleProject,
                             network: Network)(implicit ev: ApplicativeAsk[F, TraceId]): F[Operation] = {
    val projectName = ProjectName.newBuilder().setProject(project.value).build
    retryF(
      Async[F].delay(networkClient.insertNetwork(projectName, network)),
      s"com.google.cloud.compute.v1.NetworkClient.insertNetwork(${projectName.toString}, ${network.getName})"
    )
  }

  override def getSubnetwork(project: GoogleProject, region: RegionName, subnetwork: SubnetworkName)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Option[Subnetwork]] = {
    val projectRegionSubnetworkName = ProjectRegionSubnetworkName
      .newBuilder()
      .setProject(project.value)
      .setRegion(region.value)
      .setSubnetwork(subnetwork.value)
      .build
    retryF(
      recoverF(Async[F].delay(subnetworkClient.getSubnetwork(projectRegionSubnetworkName)), whenStatusCode(404)),
      s"com.google.cloud.compute.v1.SubnetworkClient.getSubnetwork(${projectRegionSubnetworkName.toString})"
    )
  }

  override def createSubnetwork(project: GoogleProject, region: RegionName, subnetwork: Subnetwork)(
    implicit ev: ApplicativeAsk[F, TraceId]
  ): F[Operation] = {
    val projectRegionName = ProjectRegionName.newBuilder().setProject(project.value).setRegion(region.value).build
    retryF(
      Async[F].delay(subnetworkClient.insertSubnetwork(projectRegionName, subnetwork)),
      s"com.google.cloud.compute.v1.SubnetworkClient.insertSubnetwork(${projectRegionName.toString}, ${subnetwork.getName})"
    )
  }

  private def buildMachineTypeUri(zone: ZoneName, machineTypeName: MachineTypeName): String =
    s"zones/${zone.value}/machineTypes/${machineTypeName.value}"

  private def buildRegionUri(googleProject: GoogleProject, regionName: RegionName): String =
    s"https://www.googleapis.com/compute/v1/projects/${googleProject.value}/regions/${regionName.value}"

  private def retryF[A](fa: F[A], loggingMsg: String)(implicit ev: ApplicativeAsk[F, TraceId]): F[A] =
    tracedRetryGoogleF(retryConfig)(blockerBound.withPermit(blocker.blockOn(fa)), loggingMsg).compile.lastOrError

}

// device name that's known to the instance (same device name when you create runtime)
final case class DeviceName(asString: String) extends AnyVal
