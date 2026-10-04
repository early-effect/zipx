package zipx.core

import zio.test.*

object SnapshotPinsSpec extends ZIOSpecDefault:

  private val pinned   = Lib("rocks.earlyeffect", "zipx-core", "0.15.0-SNAPSHOT")
  private val released = Lib("dev.zio", "zio", "2.1.26")

  private val gRelease: Gen[Any, ReleaseVersion] =
    (for
      major <- Gen.int(0, 3)
      minor <- Gen.int(0, 3)
      patch <- Gen.int(0, 3)
    yield ReleaseVersion.make(s"$major.$minor.$patch")).collect { case Right(v) => v }

  def spec = suite("SnapshotPins")(
    test("only a -SNAPSHOT row is a pin") {
      assertTrue(SnapshotPins.of(List(pinned, released)) == List(pinned))
    },
    test("every CI env revalidates changing artifacts, and zipxEnv cannot lengthen the TTL") {
      val caller = Map("COURSIER_TTL" -> EnvValue.plain("24h"), "OTHER" -> EnvValue.plain("kept"))
      val env    = SnapshotPins.ciEnv(caller)
      assertTrue(
        SnapshotPins.ciEnv(Map.empty) == Map(SnapshotPins.CoursierTtl),
        env.get("COURSIER_TTL").contains(EnvValue.plain("0s")),
        env.get("OTHER").contains(EnvValue.plain("kept")),
      )
    },
    test("the PR annotation names every pin, and the run still passes") {
      val steps =
        SnapshotPins.annotation(::(pinned, Nil)).map(_(StepContext(ModuleNode(ModuleId("_build")), None, false)))
      assertTrue(
        steps.exists(
          _.flatMap(_.run).exists(run =>
            run.contains(
              "::warning title=zipx snapshots::pinned snapshots (rocks.earlyeffect:zipx-core:0.15.0-SNAPSHOT)"
            )
          )
        ),
        steps.exists(_.forall(_.`if`.isEmpty)),
      )
    },
    test("a pin resolves the publish registry, and an extra registry beside it, not Central by habit") {
      val packages = ArtifactRegistry.GitHubPackages("iterable", "maven-packages")
      val central  = ArtifactRegistry.MavenCentral
      val lines    = SnapshotPins.pluginsSbtLines(SnapshotPins.registries(Some(packages), List(central)))
      assertTrue(
        SnapshotPins.registries(None, Nil) == List(central),
        lines.exists(_.contains(packages.snapshotRepository)),
        lines.exists(_.contains(central.snapshotRepository)),
        lines.contains(SnapshotPins.forceUpdateLine),
        SnapshotPins.isOwnResolver(SnapshotPins.resolverName(packages)),
      )
    },
    test("plugins.sbt resolves Central snapshots exactly when a plugin is pinned") {
      val snapshot = Plugin("rocks.earlyeffect", "sbt-zipx", "0.15.0-SNAPSHOT")
      val release  = Plugin("org.scalameta", "sbt-scalafmt", "2.5.4")
      assertTrue(
        ZipxCatalog.renderPlugins(List(release, snapshot)).linesIterator.contains(SnapshotPins.resolverLine),
        !ZipxCatalog.renderPlugins(List(release)).linesIterator.contains(SnapshotPins.resolverLine),
      )
    },
    test("a release refuses every snapshot it would depend on, and nothing else") {
      assertTrue(
        ReleasePlan.refuseSnapshots(List("a:b:1.0.0", "c:d:2.0.0-SNAPSHOT", "c:d:2.0.0-SNAPSHOT")) ==
          Left(ReleaseError.SnapshotPinned(::("c:d:2.0.0-SNAPSHOT", Nil))),
        ReleasePlan.refuseSnapshots(List("a:b:1.0.0")) == Right(()),
        ReleaseError.SnapshotPinned(::("c:d:2.0.0-SNAPSHOT", Nil)).message.contains("c:d:2.0.0-SNAPSHOT"),
      )
    },
    test("catalog update does not promote a line-SNAPSHOT pin onto a newer release") {
      val gPin = gRelease.map(v => DepVersion.make(s"$v-SNAPSHOT").map(v -> _)).collect { case Right(pin) => pin }
      check(gPin, gRelease) { case ((next, version), latest) =>
        val bumps  = ZipxCatalog.outdated(List(pinned.copy(version = version)), _ => Right(Some(latest: String)))
        val onRepo = ReleaseVersion.ordering.gteq(latest, next)
        val advice = SnapshotPinAdvice.message(pinned.artifact, version, Some(latest: String))
        assertTrue(
          bumps == Right(Nil),
          advice.exists(_.contains("zipxPinRelease")) == onRepo,
        )
      }
    },
  )
end SnapshotPinsSpec
