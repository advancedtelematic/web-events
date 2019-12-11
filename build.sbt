name := "web-events"
organization := "com.advancedtelematic"
scalaVersion := "2.12.10"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-Ypartial-unification")

resolvers += "ATS Releases" at "http://nexus.advancedtelematic.com:8081/content/repositories/releases"

resolvers += "ATS Snapshots" at "http://nexus.advancedtelematic.com:8081/content/repositories/snapshots"

libraryDependencies ++= {
  val akkaV = "2.5.25"
  val akkaHttpV = "10.1.10"
  val scalaTestV = "3.0.8"
  val libatsV = "0.3.0-64-gaba8100"
  val jwsV = "0.4.5-11-ga01793d"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaV % Test,
    "com.typesafe.akka" %% "akka-http-core" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % Test,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "org.scalatest"     %% "scalatest" % scalaTestV % "test",

    "ch.qos.logback" % "logback-classic" % "1.2.3",

    "com.advancedtelematic" %% "libats" % libatsV,
    "com.advancedtelematic" %% "libats-messaging" % libatsV,
    "com.advancedtelematic" %% "libats-messaging-datatype" % libatsV,
    "com.advancedtelematic" %% "libats-metrics" % libatsV,
    "com.advancedtelematic" %% "libats-metrics-akka" % libatsV,
    "com.advancedtelematic" %% "libats-metrics-prometheus" % libatsV,
    "com.advancedtelematic" %% "libats-logging" % libatsV,
    "com.advancedtelematic" %% "jw-security-core" % jwsV,
    "com.advancedtelematic" %% "jw-security-jca" % jwsV,
    "commons-codec" % "commons-codec" % "1.12"
  )
}

enablePlugins(BuildInfoPlugin)

buildInfoOptions += BuildInfoOption.ToMap

buildInfoOptions += BuildInfoOption.BuildTime


import com.typesafe.sbt.packager.docker._

dockerRepository in Docker := Some("advancedtelematic")

packageName in Docker := packageName.value

dockerAlias := dockerAlias.value.withTag(git.gitHeadCommit.value)

dockerUpdateLatest in Docker := true

defaultLinuxInstallLocation in Docker := s"/opt/${moduleName.value}"

dockerCommands := Seq(
  Cmd("FROM", "advancedtelematic/alpine-jre:adoptopenjdk-jdk8u222"),
  ExecCmd("RUN", "mkdir", "-p", s"/var/log/${moduleName.value}"),
  Cmd("ADD", "opt /opt"),
  Cmd("WORKDIR", s"/opt/${moduleName.value}"),
  ExecCmd("ENTRYPOINT", s"/opt/${moduleName.value}/bin/${moduleName.value}"),
  Cmd("RUN", s"chown -R daemon:daemon /opt/${moduleName.value}"),
  Cmd("RUN", s"chown -R daemon:daemon /var/log/${moduleName.value}"),
  Cmd("USER", "daemon")
)

enablePlugins(JavaAppPackaging)

Revolver.settings

Release.settings
