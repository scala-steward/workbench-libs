package org.broadinstitute.dsde.workbench.auth

import java.io.ByteArrayInputStream

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import org.broadinstitute.dsde.workbench.config.Config
import org.broadinstitute.dsde.workbench.dao.Google.googleIamDAO
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKey}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}

import scala.collection.JavaConverters._

// Note: we are creating a new service account private key every time we call this case class

case class ServiceAccountAuthToken(saId: WorkbenchEmail) extends AuthToken with ScalaFutures {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))

  // creates a new Google private key.  Be sure to call removePrivateKey() when you are done with it!
  private lazy val serviceAccountPrivateKey: ServiceAccountKey = {
    googleIamDAO.createServiceAccountKey(GoogleProject(Config.Projects.default), saId).futureValue
  }

  def removePrivateKey(): Unit = {
    googleIamDAO.removeServiceAccountKey(GoogleProject(Config.Projects.default), saId, serviceAccountPrivateKey.id).futureValue
  }

  override protected def buildCredential(): GoogleCredential = {
    val privateKeyJsonString = serviceAccountPrivateKey.privateKeyData.decode.get
    GoogleCredential.fromStream(new ByteArrayInputStream(privateKeyJsonString.getBytes())).createScoped(authScopes.asJava)
  }
}