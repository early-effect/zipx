package zipx.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** Docs-as-tests site builder (Test classpath; `docs/specularSite`). */
object BuildSite extends DocsSite:

  def pages = Vector(
    Overview.doc,
    WhyZipx.doc,
    QuickStart.doc,
    ExecutionModes.doc,
    MatrixCollapsePage.doc,
    Capabilities.doc,
    CustomCapabilities.doc,
    ComposingSbtCommands.doc,
    ShellAndSteps.doc,
    Verify.doc,
    AffectedDoc.doc,
    Caching.doc,
    RemoteCacheForTeams.doc,
    FromBazel.doc,
    ActionPinsDoc.doc,
    DependencyUpdates.doc,
    DockerAndDeploy.doc,
    JobConditions.doc,
    Validation.doc,
    Packs.doc,
    Settings.doc,
    Developing.doc,
  )

  override def site: SiteModel =
    val m       = meta
    val branded = EarlyEffectTheme.brand(super.site)
    branded.copy(
      clientScript = Some("assets/client.js"),
      summaryMarkdown = Some(
        s"""**zipx** generates GitHub Actions from your real sbt graph. Aggregate-first works for libraries *and*
multi-service monorepos; Layer/Graph when you need waves, per-module isolation, or multi-environment deploys.

Typed capabilities cover test, Central, GitHub Packages, docs Pages, docker, deploy, and stages you invent. Generated
CI stays reviewable by default: in-repo composites (`.github/actions/zipx-*`) parameterize JDK/sbt/cache bootstrap and
AWS login (checkout remains a prior workflow step), and
`MatrixCollapse.Auto` folds safe Graph / target fan-out into one matrix job. SHA-pinned actions, affected-only Graph
PRs, commit-stable caching, and `zipxWorkflowCheck` keep the committed YAML honest.

If you have maintained a second copy of the build (disconnected CI or a restated Bazel graph), start with **Why zipx**:
a kinder path back to one honest graph. The power is for every Scala team on Actions, and especially monorepos.

Guide: Why zipx → Quick start → Execution modes → Matrix collapse → Capabilities → Custom capabilities → Composing sbt
commands → Shell and steps → Verify → Affected → Caching → Remote cache for teams → From Bazel → Action pins →
Dependency updates → Docker and deploy → Job conditions → Validation → Packs → Settings.
"""
      ),
      installSnippets = Vector(
        {
          val install = ArtifactKind.defaultInstall(m, ArtifactKind.Plugin)
          CodeSnippet(install.heading, s"// project/plugins.sbt\n${install.code}")
        },
        CodeSnippet(
          "Generate & check",
          """sbt zipxWorkflowGenerate
git add .github/workflows/ci.yml .github/actions/
sbt zipxWorkflowCheck   # fails CI when the committed YAML drifts""",
        ),
        CodeSnippet(
          "Action pins (optional)",
          """# Prefer .github/zipx/action-pins.yml + Dependabot; see Action pins docs
zipxDependabotSync := true
sbt zipxActionsPull   # after Dependabot bumps workflow uses:""",
        ),
      ),
      brand = Some(
        Brand(
          name = m.title.getOrElse("zipx"),
          links = Vector(EarlyEffectTheme.github("https://github.com/early-effect/zipx")),
        )
      ),
    )
  end site

  override def layers: ZLayer[Any, Nothing, SiteBuilder] =
    DocsDiagrams.layers

  override def afterBuild(out: Path, result: SiteOutput): Task[Unit] =
    EarlyEffectTheme.writeLogo(out) *> copyClientBundle(out)

  private def copyClientBundle(out: Path): Task[Unit] =
    ZIO.attempt {
      val dest = out.resolve("assets/client.js")
      val src  = findClientJs.getOrElse {
        throw new RuntimeException(
          "JS client not linked; run docs/specularSite (or docsJS/fastLinkJS) first. " +
            s"Looked for marker ${clientJsMarker} and under ${repoRoot.resolve("target/out")}"
        )
      }
      Files.createDirectories(dest.getParent)
      Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
      ()
    }

  private def clientJsMarker: Path =
    repoRoot.resolve("target/specular-client-js.path")

  private def findClientJs: Option[Path] =
    readMarker.orElse(walkTargetOut)

  private def readMarker: Option[Path] =
    val marker = clientJsMarker
    if !Files.isRegularFile(marker) then None
    else
      val line = Files.readString(marker).nn.trim
      if line.isEmpty then None
      else
        val path = Paths.get(line)
        Option.when(Files.isRegularFile(path))(path)

  private def walkTargetOut: Option[Path] =
    val outRoot = repoRoot.resolve("target/out")
    if !Files.isDirectory(outRoot) then None
    else
      val stream = Files.walk(outRoot)
      try
        val found = stream
          .filter { p =>
            val s = p.toString.replace('\\', '/')
            s.endsWith("zipx-docsjs-fastopt/main.js") || s.endsWith("zipx-docsJS-fastopt/main.js")
          }
          .findFirst()
        if found.isPresent then Some(found.get.nn) else None
      finally stream.close()
    end if
  end walkTargetOut

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath.nn)(p => Option(p.getParent).orNull)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(Paths.get("").toAbsolutePath.nn)
end BuildSite
