package com.advancedtelematic.web_events.http

import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpChallenge, HttpChallenges}
import akka.http.scaladsl.server.AuthenticationFailedRejection.{CredentialsMissing, CredentialsRejected}
import akka.http.scaladsl.server._
import akka.stream.Materializer
import cats.syntax.either._
import com.advancedtelematic.jwa.HS256
import com.advancedtelematic.jws.Jws
import com.advancedtelematic.jwt.JsonWebToken
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.web_events.Settings
import com.typesafe.config.ConfigException
import io.circe.Decoder
import io.circe.parser.decode
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.codec.binary.Base64
import org.slf4j.LoggerFactory

case class ValidationResponse(active: Boolean)

object BasicAuthTokenValidator {
  lazy val logger = LoggerFactory.getLogger(this.getClass)
}

class BasicAuthTokenValidator(implicit system: ActorSystem, mat: Materializer) extends Settings {
  import BasicAuthTokenValidator.logger
  import Directives._

  def parseJwt(input: String)(
      implicit decoder: Decoder[JsonWebToken]): Either[String, JsonWebToken] =
    for {
      jws <- Jws.readSignatureCompact(input)
      payload <- decode[JsonWebToken](new String(jws.payload.data))
        .leftMap(_.getMessage)
    } yield payload

  def validate(realm: String): Directive1[String] =
    extractCredentials.flatMap {
      // bearer authentication mapped to basic auth with token in password position
      case Some(BasicHttpCredentials(_, token)) =>
        provide(token)
      case _ =>
        reject(AuthenticationFailedRejection(CredentialsMissing, HttpChallenges.basic(realm)))
    }

  private[this] def authenticateToken(serializedToken: String,
                                      verifySignatureFn: Jws.JwsVerifier): Either[String, JsonWebToken] = {
    import cats.syntax.either._
    val tokenOrError: Either[String, JsonWebToken] =
      Jws.verifySignature[JsonWebToken](serializedToken, verifySignatureFn)
    tokenOrError.ensure("The access token expired")(_.expirationTime.isAfter(Instant.now()))
  }

  def authenticateJwt(token: String, realm: String, verifySignatureFn: Jws.JwsVerifier): Directive1[JsonWebToken] =
    authenticateToken(token, verifySignatureFn) match {
      case Left(err) =>
        reject(
          AuthenticationFailedRejection(
            CredentialsRejected,
            HttpChallenge("Bearer", realm, Map("error" -> "invalid_token", "error_description" -> err))
          )
        )

      case Right(jwt) =>
        provide(jwt)
    }

  def localValidate(token: String): Directive1[JsonWebToken] = {
    import com.advancedtelematic.json.signature.JcaSupport._

    val verifier: String Either Jws.JwsVerifier = for {
      secret <- Either
        .catchOnly[ConfigException] { config.getString("auth.token.secret") }
        .leftMap(_.getMessage)
        .map[SecretKey](x => new SecretKeySpec(Base64.decodeBase64(x), "HMAC"))
      keyInfo <- HS256.verificationKey(secret).leftMap(_.getMessage)
    } yield HS256.verifier(keyInfo)

    verifier.fold(
      _ => reject(AuthorizationFailedRejection),
      x =>
        authenticateJwt(token, "auth-plus", x).flatMap { jwt =>
          logger.info(s"Token was successfully verified locally")
          provide(jwt)
      }
    )
  }

  def localValidate: Directive1[JsonWebToken] =
    validate("local").flatMap { token =>
      localValidate(token)
    }

  def fromConfig(): Directive1[Namespace] = {
    val defaultNamespace = Namespace("default")

    authProtocol match {
      case "none" => provide(defaultNamespace)
      case _ =>
        shouldVerify match {
          case "none" =>
            logger.info("Will not verify tokens")
            provide(defaultNamespace)
          case "local" =>
            logger.info("Will verify tokens locally")
            localValidate.flatMap { token =>
              val maybeNs = token.scope.underlying
                .find(_.startsWith("namespace."))
                .map(_.stripPrefix("namespace."))
              maybeNs match {
                case Some(ns) =>
                  logger.debug(s"Extracted namespace $ns.")
                  provide(Namespace(ns))
                case None =>
                  logger.error("No namespace found in the token scope.")
                  failWith(Errors.scopeLacksNamespace(token.scope.underlying))
              }
            }
        }
    }
  }
}
