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
    Packs.doc,
    Settings.doc,
    Developing.doc,
  )

  override def site: SiteModel =
    val m = meta
    super.site.copy(
      summaryMarkdown = Some(
        s"""**zipx** makes the sbt build the single source of truth for GitHub Actions CI.
It introspects the real module graph and generates Aggregate-first (Layer/Graph when you need them),
cached workflows — no hand-maintained YAML module lists.

Guide: Quick start → Execution modes → Capabilities → Custom capabilities → Verify → Caching →
Action pins → Dependency updates → Docker and deploy → Packs → Settings.
"""
      ),
      installSnippets = Vector(
        ArtifactKind.defaultInstall(m, ArtifactKind.Plugin),
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
    for
      _ <- EarlyEffectTheme.writeLogo(out)
      _ <- ZIO.attempt {
        val themeCss = out.resolve("assets/theme.css")
        val extra    = DocTables.contributionBlocks.map(_._2).mkString("\n")
        val prior    = java.nio.file.Files.readString(themeCss)
        java.nio.file.Files.writeString(themeCss, prior + "\n/* zipx doc tables (Ascent GlobalStyle) */\n" + extra)
      }
    yield ()
end BuildSite
