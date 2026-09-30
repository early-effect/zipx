scalaVersion   := "3.8.4"
version        := "1.0.0-SNAPSHOT"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-SNAPSHOT")
zipxVerify     := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

lazy val core = project
lazy val svc  = project.dependsOn(core).settings(zipxDocker := true)

lazy val root = (project in file("."))
  .aggregate(core, svc)
  .settings(publish / skip := true)

zipxCheckCommandNames := false

val stg = Target(TargetName("stg"), environment = Some("fixture-stg"), group = Some(TargetGroup("staging")))

val stgStage = settingKey[DeployStage]("the stg target's stage")
stgStage := DeployStage.Production

zipxCapabilities ++= {
  val stage = stgStage.value
  Seq(
    Capability.dockerGraph.copy(gate = Gate.Always).withMatrixCollapse(MatrixCollapse.Off),
    Capability
      .deployGraph(
        participates = _.docker,
        command = n => SbtCommand.module(n, zipxTasks.of(Compile / compile)),
        targets = _ => List(stg.copy(stage = stage)),
        gate = Gate.Always,
      )
      .withMatrixCollapse(MatrixCollapse.Off),
  )
}

val assertStaged = taskKey[Unit]("merges and labeled PRs deploy to staging from zipx-deploy.yml")
assertStaged := {
  val deploy = IO.read((LocalRootProject / baseDirectory).value / ".github" / "workflows" / "zipx-deploy.yml")
  assert(deploy.contains("pull_request:") && deploy.contains("- labeled"), "a labeled PR starts a deploy")
  assert(deploy.contains("'deploy-stg'"), "resolve runs only for a PR carrying the deploy label")
  assert(deploy.contains("pull-requests: read"), "resolve reads the merged PR's labels")
  assert(deploy.contains("- choose"), "the dispatch form's default deploys nothing")
  assert(deploy.contains("concurrency: zipx-deploy-deploy-svc-stg"), "each deploy job is its own group")
}

val assertManual = taskKey[Unit]("images and deploys moved to zipx-deploy.yml")
assertManual := {
  val root   = (LocalRootProject / baseDirectory).value
  val ci     = IO.read(root / ".github" / "workflows" / "ci.yml")
  val deploy = IO.read(root / ".github" / "workflows" / "zipx-deploy.yml")
  assert(!ci.contains("docker-svc") && !ci.contains("deploy-svc"), "ci.yml must not push images or deploy")
  assert(deploy.contains("sbt zipxDeployPlan"), "the resolve job runs zipxDeployPlan")
  assert(deploy.contains("svc/zipxImageMissing"), "the image job checks its tags")
  assert(deploy.contains("- staging"), "the target input offers the group")
  assert(deploy.contains("name: fixture-stg"), "the deploy job binds its Environment")
}

val assertOnMerge = taskKey[Unit]("images and deploys stay in ci.yml")
assertOnMerge := {
  val ci = IO.read((LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml")
  assert(ci.contains("docker-svc") && ci.contains("deploy-svc-stg"), "ci.yml keeps images and deploys under OnMerge")
}
