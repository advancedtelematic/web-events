package com.advancedtelematic.web_events.http

import java.time.Instant
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.testkit.WSProbe
import akka.stream.scaladsl.Source
import com.advancedtelematic.web_events.Messages
import com.advancedtelematic.json.signature.JcaSupport._
import com.advancedtelematic.jwa.{HS256, Hmac}
import com.advancedtelematic.jwk.KeyInfo
import com.advancedtelematic.jwk.KeyOperations.{Sign, _}
import com.advancedtelematic.jwk.KeyTypes.Octet
import com.advancedtelematic.jws.{JwsPayload, _}
import com.advancedtelematic.jwt.{JsonWebToken, _}
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.util.ResourceSpec
import com.advancedtelematic.web_events.daemon.MessageSourceProvider
import com.typesafe.config.ConfigFactory
import io.circe.Json
import org.apache.commons.codec.binary.Base64
import org.scalatest.{FunSuite, Matchers}
import org.scalatest.concurrent.ScalaFutures
import Messages._
import scala.reflect.ClassTag
import shapeless._
import cats.syntax.either._

class WebSocketResourceSpec extends FunSuite with Matchers with ScalaFutures with ResourceSpec {
  val deviceUuid = "77a1888b-9bc8-4673-8f23-a51240303db4"
  val nonMatchingDeviceUuid = "99a1888b-9bc8-4673-8f23-a51240303db4"
  val deviceName = "testDevice"
  val namespace = "default"
  val lastSeen = Instant.now().toString
  val deviceId = Some("testVin")
  val deviceType = "Vehicle"
  val packageId = PackageId("ghc", "1.0.0")
  val packageUuid = "b82ca6a4-5422-47e0-85d0-8f931006a307"
  val checksum = Json.obj("hash" -> Json.fromString("1234"))
  val eventUuid = "0d438395-73fa-438d-8a91-5a29515083e9"

  val deviceSeenMessage = DeviceSeen(namespace, deviceUuid, lastSeen)
  val deviceCreatedMessage = DeviceCreated(namespace, deviceUuid, deviceName, deviceId, deviceType, "now")
  val deviceSystemInfoChangedMessage = DeviceSystemInfoChanged(namespace, deviceUuid)
  val deviceUpdateStatusMessage = DeviceUpdateStatus(namespace, deviceUuid, "UpToDate", "now")
  val updateSpecMessage = UpdateSpec(namespace, deviceUuid, packageUuid, "Finished", "now")
  val packageBlocklistedMessage = PackageBlocklisted(namespace, packageId, "now")
  val packageCreatedMessage = PackageCreated(namespace, packageId, Some("description"), Some("ghc"), None, "now")
  val tufTargetAddedMessage = TufTargetAdded(namespace, "targetpath1", checksum, 1024, None)
  val deviceEventMessage = DeviceEventMessage(namespace, deviceUuid, eventUuid, Json.fromString("packageadded"), Instant.now(), Instant.now(), Json.Null)

  val mockMsgSrc = new MessageSourceProvider {
    override def getSource[T]()(implicit system: ActorSystem, tag: ClassTag[T]): Source[T, _] = {
      def is[M <: AnyRef : Manifest] = tag.runtimeClass.equals(manifest[M].runtimeClass)

      if (is[DeviceSeen]) {
        Source.single(deviceSeenMessage.asInstanceOf[T])
      } else if(is[DeviceCreated]) {
        Source.single(deviceCreatedMessage.asInstanceOf[T])
      } else if(is[DeviceSystemInfoChanged]) {
        Source.single(deviceSystemInfoChangedMessage.asInstanceOf[T])
      } else if(is[DeviceUpdateStatus]) {
        Source.single(deviceUpdateStatusMessage.asInstanceOf[T])
      } else if(is[UpdateSpec]) {
        Source.single(updateSpecMessage.asInstanceOf[T])
      } else if(is[PackageBlocklisted]) {
        Source.single(packageBlocklistedMessage.asInstanceOf[T])
      } else if(is[PackageCreated]) {
        Source.single(packageCreatedMessage.asInstanceOf[T])
      } else if(is[TufTargetAdded]) {
        Source.single(tufTargetAddedMessage.asInstanceOf[T])
      } else if(is[DeviceEventMessage]) {
        Source.single(deviceEventMessage.asInstanceOf[T])
      } else { throw new IllegalArgumentException("[test] Event class not supported " +
                                                 s"${tag.runtimeClass.getSimpleName}")
      }
    }
  }

  def makeJson[T](x: T)(implicit ml: MessageLike[T]): Message = {
    val js = Json.obj("type" -> Json.fromString(ml.tag.runtimeClass.getSimpleName),
      "event" -> ml.encoder(x))
    TextMessage(js.noSpaces)
  }

  def generateKeyJws: CompactSerialization = {
    val token = JsonWebToken(TokenId("token_id"), Issuer("issuer"), ClientId(UUID.randomUUID()),
      Subject(namespace),
      Audience(Set("audience")),
      Instant.now().minusSeconds(120.longValue()), Instant.now().plusSeconds(120.longValue()),
      Scope(Set(s"namespace.$namespace")))
    val key = new SecretKeySpec(Base64.decodeBase64(config.getString("auth.token.secret")), "HMAC")
    val potKeyInfo = KeyInfo[SecretKey, Octet, Sign :: Verify :: HNil, Hmac.HS256 :: HNil](key)
    val keyInfo = potKeyInfo.right.get
    val payload = JwsPayload(token)
    CompactSerialization(HS256.withKey(payload, keyInfo))
  }

  val config = ConfigFactory.load()

  val wsRoute = new WebSocketResource(mockMsgSrc).route

  test("route web socket to client") {
    val wsClient = WSProbe()

    val basicAuth = BasicHttpCredentials("bearer", generateKeyJws.value)
    WS("/events/ws", wsClient.flow).addCredentials(basicAuth) ~> wsRoute ~> check {

      isWebSocketUpgrade shouldEqual true

      val sub = wsClient.inProbe

      sub.request(n = 9)
      sub.expectNextUnordered(makeJson(deviceSeenMessage),
                              makeJson(deviceCreatedMessage),
                              makeJson(deviceSystemInfoChangedMessage),
                              makeJson(deviceUpdateStatusMessage),
                              makeJson(updateSpecMessage),
                              makeJson(packageBlocklistedMessage),
                              makeJson(packageCreatedMessage),
                              makeJson(tufTargetAddedMessage),
                              makeJson(deviceEventMessage))

      wsClient.sendCompletion()
      wsClient.expectCompletion()
    }
  }

  test("can decode device event") {
    val rawEvent =
      """
        |{"deviceUuid":"341a66bd-0dc7-4b25-93f4-f76171be4136","eventId":"73b7230b-abf2-4065-a1b4-d1948cdfef44","eventType":{"id":"DownloadComplete","version":1},"deviceTime":"2018-06-19T13:21:44Z","receivedAt":"2018-06-19T13:21:44.953Z","payload":"{\"signatures\":[{\"keyid\":\"ebe6e42bf62f4f951174c86792822c705dbefeb3328158769d5a9c47327e851a\",\"method\":\"rsassa-pss-sha256\",\"sig\":\"dwLpDBhHr5bx2yphcTqrm1VMClUQHZR4DTKO5pJ96DQFQhqP1DnkedEJNI6LgFlJZx5IabEJ/CcbMzY4plVM1beRKfora1/F879CEeRn2stSKroL35sdsBuV+gR2x9qGOsOexS7L4gQkR10HCcmwjqQfuoDuSc0jqkE6/Wcp0p7LWfdwi951Zk+S4FdVGkJmVdgC8szQfz/gu9UEOv3Jb9bVLxErSkcSboYenabIZs+9cndEtcA3cTu5FP0SVKJJnYN2UfN4VyD6/yCyGezIb/R4v9fOsukgS1T0fjhZSi6HAfK/dRhuGt5toGxbiROZE5HfRpi/dhavWb2GAmFGqw==\"}],\"signed\":{\"_type\":\"Targets\",\"expires\":\"2018-07-20T13:21:39Z\",\"targets\":{\"qemu_rocko-1ded2a7ea8c29c6472d9523aeef38f592316474bfcb69e6662e5da1b17fac0c1\":{\"hashes\":{\"sha256\":\"1ded2a7ea8c29c6472d9523aeef38f592316474bfcb69e6662e5da1b17fac0c1\"},\"length\":0,\"custom\":{\"ecuIdentifier\":\"c98184937d6ff299b2458539086132894785cf4031f217ef5348c7168d2b53c3\",\"hardwareId\":\"qemux86-64\",\"uri\":\"\",\"diff\":null,\"ecuIdentifiers\":{\"c98184937d6ff299b2458539086132894785cf4031f217ef5348c7168d2b53c3\":{\"hardwareId\":\"qemux86-64\",\"uri\":\"\",\"diff\":null}}}}},\"version\":15}}","namespace":"auth0|..."}
      """.stripMargin

    val rawJson = io.circe.parser.parse(rawEvent).valueOr(throw _)

    rawJson.as[DeviceEventMessage].valueOr(throw _) shouldBe a[DeviceEventMessage]
  }
}
