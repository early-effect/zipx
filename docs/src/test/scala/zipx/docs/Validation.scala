package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.aws.*
import zipx.core.*
import zipx.workflow.{ActionRef, SecretName}
import zio.test.*

/** When each rule fires: at the literal, at `zipxWorkflowGenerate`, or on the runner. */
object Validation extends DocSpecSuite:

  def doc = page("Validation")(
    md"""
zipx checks a lot, and *when* it checks is as much of the design as *what*. Three moments, earliest first:

```mermaid
flowchart LR
  Lit[a literal name, tag or command] --> Compile([compile error in your build])
  Run[a value assembled at runtime] --> Either([an Either you carry])
  Cfg[settings · capabilities · pin file] --> Gen([zipxWorkflowGenerate fails])
  Job[the sbt command · the secret's value] --> Push([the runner, on push])
  class Lit,Run,Cfg,Job warn
  class Compile,Either,Gen,Push happy
```

**The one rule to remember:** `Foo("literal")` is checked while your build compiles. `Foo.make(runtimeValue)` returns an
`Either` you carry. Passing a non-literal to the first is itself a compile error telling you to use the second, so there
is no way to skip the check by accident.
""",
    section("Compile time: every named value")(
      md"""
Every one of these is a [neotype](https://github.com/kitlangton/neotype) wrapper with an `inline apply`, so a bad
literal fails your build, not your CI run:

| Module | Types |
|---|---|
| `zipx-shell` | `ShText`, `SquoteText`, `ParamText`, `ScriptLine`, `VarName`, `GlobPattern`, `ProgramName`, `HeredocTag`, `ExitCode`, `FileDescriptor` |
| `zipx-workflow` | `JobId`, `StepId`, `SecretName`, `EnvName`, `OutputName`, `MatrixAxis`, `ContextPath`, `ActionRef`, `EventName`, `FunctionName`, `ExprLiteral`, `RawExpr`, `CronHour`, `CronMinute`, `CronExpr` |
| `zipx-core` | `ModuleId`, `CapabilityName`, `TargetName`, `WorkflowName`, `RunnerOs`, `JdkVersion`, `NodeVersion`, `SbtCommandText` |
| `zipx-aws` | `AwsAccountId`, `AwsRegion`, `EcrRepository`, `ImageTag` |

Structure is checked the same way, by making the wrong shape unrepresentable rather than by rejecting it later:
`StepBuilder` cannot produce a step with both `uses:` and `run:`, or `with:` on a `run:` step, so the two rules
[[zipx.workflow.Step.validate]] exists for are unreachable from `Step.run` / `Step.uses`. `EcrRegistry` has no
constructor without a region, which is why a generated login step cannot omit `aws-region`. See **Shell and steps**.
""",
      exampleValue {
        List(
          s"a module id GitHub would reject: ${ModuleId.make("café").isLeft}",
          s"a capability name with a slash: ${CapabilityName.make("docker/stg").isLeft}",
          s"an unpinned action ref: ${ActionRef.make("actions/checkout").isLeft}",
          s"a reserved secret prefix: ${SecretName.make("GITHUB_FOO").isLeft}",
          s"an account id one digit short: ${AwsAccountId.make("11112222333").isLeft}",
          s"a branch name used as an image tag: ${ImageTag.make("main-feat/x-abc123").isLeft}",
        ).mkString("\n")
      }.assert(out =>
        assertTrue(
          out.contains("a module id GitHub would reject: true"),
          out.contains("a capability name with a slash: true"),
          out.contains("an unpinned action ref: true"),
          out.contains("a reserved secret prefix: true"),
          out.contains("an account id one digit short: true"),
          out.contains("a branch name used as an image tag: true"),
        )
      ),
    ),
    section("Why some settings are plain Strings")(
      md"""
`zipxTestTask`, `zipxPublishTask` and `zipxCacheRehydrateTask` are `String`, not `SbtCommand`, and that is deliberate: a
`build.sbt` assigns them as ordinary strings, and an opaque type in an sbt `settingKey` would need a `JsonFormat`. The
check moves to the plugin, where every other config value is already checked, so `zipxTestTask := "api/tets\\n"` fails
`zipxWorkflowGenerate` naming the setting rather than emitting a broken `run:` line.

`zipxTasks` and `cmd"…"` are the typed route that skips this entirely: they take real sbt `TaskKey`s, so
`zipxTasks.once(Fmt, scalafmtCheckAll)` is code-completed and cannot name a task that does not exist. See
**Custom capabilities**.
"""
    ),
    section("Generate time: everything assembled from more than one file")(
      md"""
A build's CI config is spread across `build.sbt`, `project/*.scala`, packs, and the pin file. Nothing checked at a
literal can see the *combination*, so [[zipx.core.Planner]] checks that, and `zipxWorkflowGenerate` fails rather than
writing a file:

| Check | What it catches |
|---|---|
| `validateCapabilities`: `Gate.AffectedOnly` | an unimplemented seam that would silently behave as `Gate.Always` |
| `validateCapabilities`: `needsCapabilities` cycle | two capabilities each waiting on the other |
| `validateWorkflowCall` | `container:` / `services:` beside `workflowCall`, which GitHub rejects |
| `validateSharedTargets` | a per-destination `condition` or `environment` on a `SharedJob` capability |
| `validateSatisfiable` | a gate/condition conjunction that can never be true (see **Job conditions**) |
| `ModuleGraph.make` | a dependency cycle, or two modules with one id |
| `ModuleId.make` on every sbt project id | a project id sbt allows and a GitHub job id does not |
| `Step.validate` / `YamlPrinter.problem` at render | a hand-built step, or content YAML would mangle |
| `ActionPinFile.parse` | a typo'd pin key, an unpinned ref, a key naming a different action |

The last one has a deliberate asymmetry worth knowing: a pin file that is *present* must be readable in full, so a bad
line fails the build rather than falling back to the jar pin for that field. An *absent* file still falls back silently,
because that is the documented default. See **Action pins**.
""",
      exampleValue {
        val a       = Capability.publish.copy(name = CapabilityName("a"), needsCapabilities = List(CapabilityName("b")))
        val b       = Capability.publish.copy(name = CapabilityName("b"), needsCapabilities = List(CapabilityName("a")))
        val affects = Capability.publish.copy(gate = Gate.AffectedOnly)
        def failure(caps: Capability*): String =
          scala.util.Try(DocsRender.plan(caps*)).fold(_.getMessage, _ => "planned (no error)")
        List(
          failure(a, b),
          failure(affects),
          ActionPinFile.parse("chekout: actions/checkout@v7.0.1").fold(identity, _ => "parsed"),
          ActionPinFile.parse("checkout: actions/setup-java@v5").fold(identity, _ => "parsed"),
        ).mkString("\n")
      }.assert(out =>
        assertTrue(
          out.contains("needsCapabilities cycle among a, b"),
          out.contains("Gate.AffectedOnly is not implemented"),
          out.contains("unknown pin 'chekout'"),
          out.contains("must name actions/checkout"),
        )
      ),
    ),
    section("Generate time: warnings for the escape hatches")(
      md"""
`Script.raw`, `Step.runRaw`, `JobCondition.raw` and `SbtCommand.unchecked` exist because no AST covers everything. They
are not holes: the text rules that would corrupt the generated file still apply. What they skip is the *structure*, so
`api/tets` is a failing job rather than a compile error.

`Steps.rawWarnings` reports every one at generate time, naming the bundle or capability, because **an escape hatch you
cannot see being used is one you cannot review**. A warning, not an error: the hatch is legitimate, and zipx does not
get to decide that your sbt syntax is wrong. Reaching for a hatch inside a bare lambda instead of a named
`Steps.built(...)` bundle hides it from this report, which is the incentive to use the bundle.
""",
      exampleValue {
        SbtCommand.unchecked("api/tets") match
          case Left(error) => s"unexpected rejection: $error"
          case Right(cmd)  =>
            val smoke = Capability.custom(name = CapabilityName("smoke"), command = _ => cmd)
            Steps.rawWarnings(List(smoke), DocsFixtures.config).mkString("\n")
      }.assert(out =>
        assertTrue(
          out.contains("capability 'smoke' uses an unchecked sbt command: api/tets"),
          out.contains("failing job rather than a compile error"),
        )
      ),
    ),
    section("What is left to the runner")(
      md"""
Two things zipx does **not** check, on purpose:

- **Whether the sbt command succeeds.** Even a `Built` command is only known to be well-formed text naming a real
  module and task. Whether `api/test` passes is the job's business.
- **Whether a secret exists.** zipx handles secret *names*, never values, so `secret"DEPLOY_ROLE"` checks the name's
  shape and nothing more. A name that is not configured in the repository renders as an empty string on the runner,
  which GitHub does not treat as an error.

And one rule the whole library follows: nothing in `modules/*/src/main` throws. Failures are `Either`, and
`ZipxPlugin.orFail` is the single seam where one becomes a thrown sbt error, because sbt's task contract is that a task
fails by throwing. A library caller still sees the `Either`.
"""
    ),
  )
end Validation
