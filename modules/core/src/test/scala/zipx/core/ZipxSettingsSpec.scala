package zipx.core

import zio.test.*

object ZipxSettingsSpec extends ZIOSpecDefault:

  def spec = suite("ZipxSettings")(
    test("catalog names are unique") {
      assertTrue(ZipxSettings.names.size == ZipxSettings.all.size)
    },
    test("catalog covers keys that previously drifted from the Settings docs") {
      assertTrue(
        ZipxSettings.names.contains("zipxAffectedDeploy"),
        ZipxSettings.names.contains("zipxActionRows"),
        ZipxSettings.names.contains("zipxVerify"),
        ZipxSettings.names.contains("zipxActionUpdate"),
        ZipxSettings.names.contains("zipxLeftoverSteward"),
        ZipxSettings.names.contains("zipxAdvisoryCheck"),
        ZipxSettings.names.contains("zipxMatrixCollapse"),
        ZipxSettings.names.contains("zipxPinUpdate"),
        ZipxSettings.names.contains("zipxPinPrGate"),
        ZipxSettings.names.contains("zipxPreRelease"),
        ZipxSettings.names.contains("zipxDepUpdate"),
        ZipxSettings.names.contains("zipxVersionUpdates"),
        ZipxSettings.names.contains("zipxVersionUpdatesSchedule"),
        ZipxSettings.names.contains("zipxVersionUpdatesExtraSteps"),
        ZipxSettings.names.contains("zipxVersionUpdatesPreSteps"),
        ZipxSettings.names.contains("zipxCatalogGenerate"),
        ZipxSettings.names.contains("zipxVersions"),
        ZipxSettings.names.contains("zipxPins"),
        ZipxSettings.names.contains("zipxCheckDeps"),
        ZipxSettings.names.contains("zipxEmitSelf"),
        ZipxSettings.names.contains("zipxSelfPlugins"),
        ZipxSettings.names.contains("zipxShips"),
        ZipxSettings.names.contains("zipxMatrixRoot"),
        ZipxSettings.names.contains("zipxModverBump"),
      )
    },
    test("build / project / task partitions cover every entry exactly once") {
      val partitioned = ZipxSettings.buildLevel ++ ZipxSettings.projectLevel ++ ZipxSettings.tasks
      assertTrue(
        partitioned.map(_.name: String).toSet == ZipxSettings.names,
        partitioned.size == ZipxSettings.all.size,
      )
    },
    test("typed defaults keep a value; derived defaults are docs-only summaries") {
      val (values, derived) = ZipxSettings.all.partitionMap {
        case d if d.kind == SettingKind.Setting =>
          d.default match
            case SettingDefault.Value(_, _) => Left(d.name: String)
            case SettingDefault.Derived(_)  => Right(d.name: String)
        case d => Right(d.name: String) // tasks / inputs use Derived("—")
      }
      assertTrue(
        values.contains("zipxCapabilities"),
        values.contains("zipxCacheRehydrateTask"),
        derived.contains("zipxPublish"),
        derived.contains("zipxDocker"),
        derived.contains("zipxCiRelevant"),
        derived.contains("zipxWorkflowGenerate"),
      )
    },
    test("SbtCommand settings document the shared defaults") {
      assertTrue(
        ZipxSettings.cacheRehydrateTask.default match
          case SettingDefault.Value(v, _) => v == PlanConfig.DefaultCacheRehydrateTask
          case _                          => false
        ,
        ZipxSettings.testTask.default match
          case SettingDefault.Value(v, _) => v == ModuleNode.DefaultTestTask
          case _                          => false
        ,
        ZipxSettings.publishTask.default match
          case SettingDefault.Value(v, _) => v == ModuleNode.DefaultPublishTask
          case _                          => false,
      )
    },
    suite("TypeLabel.shorten")(
      test("strips neotype $package / .Type and java.lang so markdown math stays intact") {
        assertTrue(
          TypeLabel.shorten("PlanConfig$package.WorkflowName.Type") == "WorkflowName",
          TypeLabel.shorten("java.lang.String") == "String",
          TypeLabel.shorten(
            "scala.collection.immutable.Map[Capability$package.CapabilityName, zipx.core.MatrixCollapse]"
          ) == "Map[CapabilityName, MatrixCollapse]",
          TypeLabel.shorten(
            "Function1[zipx.core.StepContext, scala.collection.immutable.List[zipx.workflow.Step]]"
          ) == "StepContext => List[Step]",
        )
      },
      test("catalog type labels are docs-friendly (no $package, java.lang, or Function1)") {
        val labels = ZipxSettings.all.map(d => d.typeLabel: String)
        assertTrue(
          labels.forall(l => !l.contains("$") && !l.contains("java.lang") && !l.contains("Function1")),
          labels.contains("WorkflowName"),
          labels.contains("String"),
          labels.contains("Map[CapabilityName, MatrixCollapse]"),
          labels.exists(_.contains("=>")),
        )
      },
    ),
  )
end ZipxSettingsSpec
