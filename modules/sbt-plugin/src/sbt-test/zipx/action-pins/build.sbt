MyVersions.settings
version        := "1.0.0-ci"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-ci")
zipxVerify := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

lazy val root = (project in file("."))
  .settings(publish / skip := true)

val catalogSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
val bumpedSha  = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

val assertActionPins = taskKey[Unit]("generated uses: comes from the catalog Action val")
assertActionPins := {
  val ci = IO.read((LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml")
  assert(ci.contains(s"actions/checkout@$catalogSha"), s"expected catalog checkout SHA, got:\n$ci")
  assert(ci.contains("# v0.0.1") || ci.contains("v0.0.1"), "expected catalog version label")
}

val assertActionRewrite = taskKey[Unit]("rewrite the Action constructor the same way zipxActionUpdate does")
assertActionRewrite := {
  val file   = (LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala"
  val action = Action("actions/checkout", "v0.0.1", sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
  val bump   = zipx.core.ActionBump(action, zipx.core.BumpKind.Minor, "v9.9.9", bumpedSha)
  ZipxCatalog.applyActionBumps(IO.read(file), List(bump)) match
    case Left(err)  => sys.error(err)
    case Right(out) =>
      IO.write(file, out)
      assert(out.contains(s"""Action("actions/checkout", "v9.9.9", sha = "$bumpedSha")"""), out)
}

val assertActionRewritten = taskKey[Unit]("generate after rewrite uses the new SHA")
assertActionRewritten := {
  val ci      = IO.read((LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml")
  val catalog = IO.read((LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala")
  assert(catalog.contains(s"""sha = "$bumpedSha""""), catalog)
  assert(ci.contains(s"actions/checkout@$bumpedSha"), s"expected rewritten SHA, got:\n$ci")
  assert(!ci.contains(s"actions/checkout@$catalogSha"), "old SHA should be gone from ci.yml")
}
