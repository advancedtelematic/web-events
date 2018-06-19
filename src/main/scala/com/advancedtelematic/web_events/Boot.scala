package com.advancedtelematic.web_events

import java.time.Instant

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.{Directives, Route}
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.http.LogDirectives.logResponseMetrics
import com.advancedtelematic.libats.http.VersionDirectives.versionHeaders
import com.advancedtelematic.libats.http.monitoring.MetricsSupport
import com.advancedtelematic.libats.messaging.MessageListener
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.web_events.daemon.WebMessageBusListener
import com.advancedtelematic.libats.messaging.daemon.MessageBusListenerActor.Subscribe
import com.advancedtelematic.web_events.http.WebEventsRoutes
import com.typesafe.config.ConfigFactory
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto._
import io.circe.java8.time._

// simpler versions of the messages with stringly types
trait Messages {
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
  case class PackageBlacklisted(namespace: String, packageId: PackageId, timestamp: String)
  case class UpdateSpec(namespace: String, device: String, packageUuid: String, status: String, timestamp: String)
  case class TufTargetAdded(namespace: String, filename: String, checksum: Json, length: Long, custom: Option[Json])
  case class DeviceEventMessage(namespace: String, deviceUuid: String, eventId: String, eventType: String,
                                deviceTime: Instant, receivedAt: Instant, payload: Json)

  implicit val deviceSeenMessageLike = MessageLike[DeviceSeen](_.uuid)
  implicit val deviceCreatedMessageLike = MessageLike[DeviceCreated](_.uuid)
  implicit val deviceSystemInfoChangedLike = MessageLike[DeviceSystemInfoChanged](_.uuid)
  implicit val deviceUpdateStatusMessageLike = MessageLike[DeviceUpdateStatus](_.device)
  implicit val packageCreatedMessageLike = MessageLike[PackageCreated](_.packageId.mkString)
  implicit val blacklistedPackageMessageLike = MessageLike[PackageBlacklisted](_.packageId.mkString)
  implicit val updateSpecMessageLike = MessageLike[UpdateSpec](_.device.toString)
  implicit val tufTargetAddedMessageLike = MessageLike[TufTargetAdded](_.filename)
  implicit val deviceEventMessageLike = MessageLike[DeviceEventMessage](_.namespace)
}

trait Settings {
  lazy val config = ConfigFactory.load()

  val host = config.getString("server.host")
  val port = config.getInt("server.port")

  val authProtocol = config.getString("auth.protocol")
  val shouldVerify = config.getString("auth.verification")
  lazy val authPlusUri = Uri(config.getString("authplus.api.uri"))
  lazy val clientId = config.getString("authplus.client.id")
  lazy val clientSecret = config.getString("authplus.client.secret")
}

object Boot extends BootApp
  with Directives
  with Settings
  with VersionInfo
  with MetricsSupport
  with Messages {

  // SI-1938
  override lazy val config = ConfigFactory.load()

  log.info(s"Starting $version on http://$host:$port")

  def startListener[T <: AnyRef](name: String)(implicit ml: MessageLike[T]): Unit = {
    val listener = system.actorOf(MessageListener.props[T](config,
      WebMessageBusListener.action[T], metricRegistry), name)
    listener ! Subscribe
  }

  List(
    deviceSeenMessageLike -> "device-seen",
    deviceCreatedMessageLike -> "device-created",
    deviceSystemInfoChangedLike -> "device-system-info-changed",
    deviceUpdateStatusMessageLike -> "device-update-status",
    packageCreatedMessageLike -> "package-created",
    blacklistedPackageMessageLike -> "package-blacklisted",
    updateSpecMessageLike -> "update-spec",
    tufTargetAddedMessageLike -> "tuf-target-added",
    deviceEventMessageLike -> "device-event"
  ).foreach { case (ml, name) => startListener(name)(ml) }

  val routes: Route =
    (versionHeaders(version) & logResponseMetrics(projectName)) {
      new WebEventsRoutes().routes
    }

  Http().bindAndHandle(routes, host, port)
}
