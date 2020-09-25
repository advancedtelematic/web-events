package com.advancedtelematic.web_events

import java.time.Instant

import akka.actor.ActorRef
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.{Directives, Route}
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.http.LogDirectives.logResponseMetrics
import com.advancedtelematic.libats.http.VersionDirectives.versionHeaders
import com.advancedtelematic.libats.messaging.{MessageListener, MessageListenerSupport, NoOpListenerMonitor}
import com.advancedtelematic.libats.messaging.daemon.MessageBusListenerActor.Subscribe
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.metrics.prometheus.PrometheusMetricsSupport
import com.advancedtelematic.metrics.{AkkaHttpRequestMetrics, MetricsSupport}
import com.advancedtelematic.web_events.daemon.WebMessageBusListener
import com.advancedtelematic.web_events.http.WebEventsRoutes
import com.typesafe.config.ConfigFactory
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

// simpler versions of the messages with stringly types
object Messages {
  case class PackageId(name: String, version: String) {
    def mkString: String = s"$name-$version"
  }

  object PackageId {
    import io.circe.generic.semiauto._
    import io.circe.{Decoder, Encoder}

    implicit val packageIdEncoder: Encoder[PackageId] = deriveEncoder
    implicit val packageIdDecoder: Decoder[PackageId] = deriveDecoder
  }

  case class DeviceSeen(namespace: String, uuid: String, lastSeen: String)
  case class DeviceCreated(namespace: String, uuid: String, deviceName: String, deviceId: Option[String],
                           deviceType: String, timestamp: String)
  case class DeviceSystemInfoChanged(namespace: String, uuid: String)
  case class DeviceUpdateStatus(namespace: String, device: String, status: String, timestamp: String)
  case class PackageCreated(namespace: String, packageId: PackageId, description: Option[String],
                            vendor: Option[String], signature: Option[String], timestamp: String)
  case class PackageBlocklisted(namespace: String, packageId: PackageId, timestamp: String)
  case class UpdateSpec(namespace: String, device: String, packageUuid: String, status: String, timestamp: String)
  case class TufTargetAdded(namespace: String, filename: String, checksum: Json, length: Long, custom: Option[Json])
  case class DeviceEventMessage(namespace: String, deviceUuid: String, eventId: String, eventType: Json,
                                deviceTime: Instant, receivedAt: Instant, payload: Json)

  implicit val deviceSeenEncoder: Encoder[DeviceSeen] = deriveEncoder
  implicit val deviceSeenDecoder: Decoder[DeviceSeen] = deriveDecoder
  implicit val deviceCreatedEncoder: Encoder[DeviceCreated] = deriveEncoder
  implicit val deviceCreatedDecoder: Decoder[DeviceCreated] = deriveDecoder
  implicit val deviceSystemInfoChangedEncoder: Encoder[DeviceSystemInfoChanged] = deriveEncoder
  implicit val deviceSystemInfoChangedDecoder: Decoder[DeviceSystemInfoChanged] = deriveDecoder
  implicit val deviceUpdateStatusEncoder: Encoder[DeviceUpdateStatus] = deriveEncoder
  implicit val deviceUpdateStatusDecoder: Decoder[DeviceUpdateStatus] = deriveDecoder
  implicit val packageCreatedEncoder: Encoder[PackageCreated] = deriveEncoder
  implicit val packageCreatedDecoder: Decoder[PackageCreated] = deriveDecoder
  implicit val packageBlocklistedEncoder: Encoder[PackageBlocklisted] = deriveEncoder
  implicit val packageBlocklistedDecoder: Decoder[PackageBlocklisted] = deriveDecoder
  implicit val updateSpecEncoder: Encoder[UpdateSpec] = deriveEncoder
  implicit val updateSpecDecoder: Decoder[UpdateSpec] = deriveDecoder
  implicit val tufTargetAddedEncoder: Encoder[TufTargetAdded] = deriveEncoder
  implicit val tufTargetAddedDecoder: Decoder[TufTargetAdded] = deriveDecoder
  implicit val deviceEventMessageEncoder: Encoder[DeviceEventMessage] = deriveEncoder
  implicit val deviceEventMessageDecoder: Decoder[DeviceEventMessage] = deriveDecoder

  implicit val deviceSeenMessageLike = MessageLike[DeviceSeen](_.uuid)
  implicit val deviceCreatedMessageLike = MessageLike[DeviceCreated](_.uuid)
  implicit val deviceSystemInfoChangedLike = MessageLike[DeviceSystemInfoChanged](_.uuid)
  implicit val deviceUpdateStatusMessageLike = MessageLike[DeviceUpdateStatus](_.device)
  implicit val packageCreatedMessageLike = MessageLike[PackageCreated](_.packageId.mkString)
  implicit val blocklistedPackageMessageLike = MessageLike[PackageBlocklisted](_.packageId.mkString)
  implicit val updateSpecMessageLike = MessageLike[UpdateSpec](_.device)
  implicit val tufTargetAddedMessageLike = MessageLike[TufTargetAdded](_.filename)
  implicit val deviceEventMessageLike = MessageLike[DeviceEventMessage](_.namespace)
}

trait Settings {
  lazy val config = ConfigFactory.load()

  val host = config.getString("server.host")
  val port = config.getInt("server.port")

  val authProtocol = config.getString("auth.protocol")
  val shouldVerify = config.getString("auth.verification")
}

object Boot extends BootApp
  with Directives
  with Settings
  with VersionInfo
  with MetricsSupport
  with MessageListenerSupport
  with AkkaHttpRequestMetrics
  with PrometheusMetricsSupport {

  import Messages._

  // SI-1938
  override lazy val config = ConfigFactory.load()

  log.info(s"Starting $version on http://$host:$port")

  private def listen[T <: AnyRef](implicit ml: MessageLike[T]): ActorRef =
    startListener[T](WebMessageBusListener.action[T], NoOpListenerMonitor)

  List(
    deviceSeenMessageLike,
    deviceCreatedMessageLike,
    deviceSystemInfoChangedLike,
    deviceUpdateStatusMessageLike,
    packageCreatedMessageLike,
    blocklistedPackageMessageLike,
    updateSpecMessageLike,
    tufTargetAddedMessageLike,
    deviceEventMessageLike
  ).foreach(listen(_))

  val routes: Route =
    (versionHeaders(version) & requestMetrics(metricRegistry) & logResponseMetrics(projectName)) {
      prometheusMetricsRoutes ~
      new WebEventsRoutes().routes
    }

  Http().bindAndHandle(routes, host, port)
}
