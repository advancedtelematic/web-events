import sbtrelease.ReleaseStateTransformations.{setReleaseVersion => _, _}
import sbt.Keys._
import sbt._
import com.typesafe.sbt.SbtNativePackager.Docker

import sbtrelease.ReleasePlugin.autoImport._

object Release {

  lazy val settings = {

    Seq(
      releaseProcess := Seq(
        checkSnapshotDependencies,
        ReleaseStep(releaseStepTask(publish in Docker))
      ),
      releaseIgnoreUntrackedFiles := true
    )
  }
}
