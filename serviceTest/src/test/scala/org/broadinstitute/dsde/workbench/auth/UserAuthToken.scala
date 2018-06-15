package org.broadinstitute.dsde.workbench.auth

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import org.broadinstitute.dsde.workbench.config.{ServiceTestConfig, Credentials}

import scala.collection.JavaConverters._

case class UserAuthToken(user: Credentials) extends AuthToken {
  override def buildCredential(): GoogleCredential = {
    val pemfile = new java.io.File(ServiceTestConfig.GCS.pathToQAPem)
    val email = ServiceTestConfig.GCS.qaEmail

    new GoogleCredential.Builder()
      .setTransport(httpTransport)
      .setJsonFactory(jsonFactory)
      .setServiceAccountId(email)
      .setServiceAccountPrivateKeyFromPemFile(pemfile)
      .setServiceAccountScopes(authScopes.asJava)
      .setServiceAccountUser(user.email)
      .build()
  }
}
