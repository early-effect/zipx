MyVersions.settings
version        := "1.0.0-ci"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-ci")

lazy val root = (project in file("."))
  .settings(publish / skip := true)

zipxPinFeeds += {
  val dest = (LocalRootProject / baseDirectory).value / "applied.txt"
  PinFeed(
    name = PinFeedName("cdn"),
    classify = VersionStrategy.npm,
    lookup = pin =>
      Right(
        Some(
          PinCandidate(
            "1.2.4",
            sha256 = Some("cafebabe"),
            purl = pin.purl.map(_ => Purl("pkg:npm/lib-a@1.2.4")),
          )
        )
      ),
    materialize = { (pin, to) =>
      IO.write(dest, s"${pin.id}=${to.version}\n")
      Right(())
    },
    submitSnapshot = true,
  )
}

val assertPinFeeds = taskKey[Unit]("assert pin-check job and companion workflows")
assertPinFeeds := {
  val root  = (LocalRootProject / baseDirectory).value
  val ci    = IO.read(root / ".github" / "workflows" / "ci.yml")
  val check = IO.read(root / ".github" / "workflows" / "zipx-pin-check.yml")
  val snap  = IO.read(root / ".github" / "workflows" / "zipx-pin-snapshot.yml")
  assert(ci.contains("pin-check:"), "ci.yml should contain the pin-check job")
  assert(ci.contains("zipxPinCheckPr") || ci.contains("zipxPinCheckPr'"), "pin-check should run zipxPinCheckPr")
  assert(ci.contains("pull_request"), "pin-check should be pull_request gated")
  assert(check.contains("workflow_dispatch"), "pin-check companion should allow dispatch")
  assert(check.contains("sbt zipxPinCheck"), "pin-check companion should run zipxPinCheck")
  assert(snap.contains("sbt zipxPinSubmit"), "snapshot companion should run zipxPinSubmit")
  assert(check.indexOf("actions/checkout@") < check.indexOf("sbt zipxPinCheck"), "checkout before sbt on pin-check")
  assert(snap.indexOf("actions/checkout@") < snap.indexOf("sbt zipxPinSubmit"), "checkout before sbt on snapshot")
}

val assertPinUpdate = taskKey[Unit]("assert zipxPinUpdate yes rewrote the catalog Pin")
assertPinUpdate := {
  val catalog = IO.read((LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala")
  val sidecar = IO.read((LocalRootProject / baseDirectory).value / "applied.txt")
  assert(
    catalog.contains("""Pin("cdn", "lib-a", "1.2.4", sha256 = "cafebabe", purl = "pkg:npm/lib-a@1.2.4")"""),
    s"expected bumped Pin constructor, got $catalog",
  )
  assert(!catalog.contains("1.2.3"), s"old version should be gone, got $catalog")
  assert(sidecar.contains("lib-a=1.2.4"), s"expected lib-a=1.2.4, got $sidecar")
}
