package zipx.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.*
import specular.site.*
import zio.*

import java.nio.file.Path

/** Docs-as-tests site builder (Test classpath; `docs/specularSite`). */
object BuildSite extends DocsSite:

  def pages = Vector(
    Overview.doc,
    QuickStart.doc,
    ExecutionModes.doc,
    Capabilities.doc,
    CustomCapabilities.doc,
    Verify.doc,
    Caching.doc,
    ActionPinsDoc.doc,
    DependencyUpdates.doc,
    DockerAndDeploy.doc,
    JobConditions.doc,
    Packs.doc,
    Settings.doc,
    Developing.doc,
  )

  override def site: SiteModel =
    val m = meta
    super.site.copy(
      summaryMarkdown = Some(
        s"""**zipx** generates GitHub Actions from your real sbt graph. Aggregate-first works for libraries *and*
multi-service monorepos; Layer/Graph when you need waves, per-module isolation, or multi-environment deploys. Typed
capabilities cover test, Central, GitHub Packages, docker, and deploy so you do not hand-roll YAML module lists.
Drift fails the PR via `zipxWorkflowCheck`.

Especially compelling if you have maintained a second copy of the build (disconnected CI or a restated Bazel graph);
the power is for every Scala team on Actions, and especially monorepos.

Guide: Quick start → Execution modes → Capabilities → Custom capabilities → Verify → Caching →
Action pins → Dependency updates → Docker and deploy → Job conditions → Packs → Settings.
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
git add .github/workflows/ci.yml
sbt zipxWorkflowCheck   # fails CI when the committed YAML drifts""",
        ),
        CodeSnippet(
          "Action pins (optional)",
          """# Prefer .github/zipx/action-pins.yml + Dependabot; see Action pins docs
zipxDependabotSync := true
sbt zipxActionsPull   # after Dependabot bumps workflow uses:""",
        ),
      ),
      logo = Some(EarlyEffectTheme.logoHref),
      logoLink = Some("https://www.earlyeffect.rocks/"),
      brand = Some(
        Brand(
          name = m.title.getOrElse("zipx"),
          links = Vector(EarlyEffectTheme.github("https://github.com/early-effect/zipx")),
        )
      ),
    )
  end site

  override def layers: ZLayer[Any, Nothing, SiteBuilder] =
    ZLayer.make[SiteBuilder](
      MarkdownRenderer.live,
      ExampleRunner.live,
      HtmlSsr.live,
      SiteWriter.live,
      NavBuilder.live,
      EarlyEffectTheme.live,
      PageTemplate.live,
      LandingTemplate.live,
      SiteBuilder.live,
    )

  override def afterBuild(out: Path, result: SiteOutput): Task[Unit] =
    EarlyEffectTheme.writeLogo(out)
end BuildSite
