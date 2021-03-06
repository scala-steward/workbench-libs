package org.broadinstitute.dsde.workbench.google2

import com.google.cloud.dataproc.v1.{Cluster, ClusterConfig, GceClusterConfig, InstanceGroupConfig}
import org.broadinstitute.dsde.workbench.google2.DataprocRole.{Master, SecondaryWorker, Worker}
import org.broadinstitute.dsde.workbench.util2.{PropertyBasedTesting, WorkbenchTestSuite}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._

class GoogleDataprocInterpreterSpec
    extends AnyFlatSpecLike
    with Matchers
    with WorkbenchTestSuite
    with PropertyBasedTesting {
  "getAllInstanceNames" should "return instances for a cluster correctly" in {
    val cluster = Cluster
      .newBuilder()
      .setConfig(
        ClusterConfig
          .newBuilder()
          .setGceClusterConfig(
            GceClusterConfig
              .newBuilder()
              .setZoneUri("https://www.googleapis.com/compute/v1/projects/some-project/zones/us-central1-a")
          )
          .setMasterConfig(InstanceGroupConfig.newBuilder().addInstanceNames("master"))
          .setWorkerConfig(
            InstanceGroupConfig.newBuilder().addAllInstanceNames(List("worker0", "worker1", "worker2").asJava)
          )
          .setSecondaryWorkerConfig(
            InstanceGroupConfig
              .newBuilder()
              .setIsPreemptible(true)
              .addAllInstanceNames(List("secondaryWorker0", "secondaryWorker1").asJava)
          )
      )
      .build()

    val res = GoogleDataprocInterpreter.getAllInstanceNames(cluster)
    val expectedResult = Map(
      DataprocRoleZonePreemptibility(Master, ZoneName("us-central1-a"), false) -> Set(InstanceName("master")),
      DataprocRoleZonePreemptibility(Worker, ZoneName("us-central1-a"), false) -> Set(InstanceName("worker0"),
                                                                                      InstanceName("worker1"),
                                                                                      InstanceName("worker2")
      ),
      DataprocRoleZonePreemptibility(SecondaryWorker, ZoneName("us-central1-a"), true) -> Set(
        InstanceName("secondaryWorker0"),
        InstanceName("secondaryWorker1")
      )
    )
    res shouldBe expectedResult
  }

  "countPreemptibles" should "count preemptibles correctly" in {
    val singleNode = Map(
      DataprocRoleZonePreemptibility(Master, ZoneName("us-central1-a"), false) -> Set(InstanceName("master"))
    )
    GoogleDataprocInterpreter.countPreemptibles(singleNode) shouldBe 0

    val workersNoPreemptibles = Map(
      DataprocRoleZonePreemptibility(Master, ZoneName("us-central1-a"), false) -> Set(InstanceName("master")),
      DataprocRoleZonePreemptibility(Worker, ZoneName("us-central1-a"), false) -> Set(InstanceName("worker1"),
                                                                                      InstanceName("worker2")
      )
    )
    GoogleDataprocInterpreter.countPreemptibles(workersNoPreemptibles) shouldBe 0

    val workersAndPreemptibles = Map(
      DataprocRoleZonePreemptibility(Master, ZoneName("us-central1-a"), false) -> Set(InstanceName("master")),
      DataprocRoleZonePreemptibility(Worker, ZoneName("us-central1-a"), false) -> Set(InstanceName("worker1"),
                                                                                      InstanceName("worker2")
      ),
      DataprocRoleZonePreemptibility(Worker, ZoneName("us-central1-a"), true) -> Set(InstanceName("preemptible1"),
                                                                                     InstanceName("preemptible2"),
                                                                                     InstanceName("preemptible3"),
                                                                                     InstanceName("preemptible4"),
                                                                                     InstanceName("preemptible5")
      )
    )
    GoogleDataprocInterpreter.countPreemptibles(workersAndPreemptibles) shouldBe 5
  }
}
