package zipx.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.*
import specular.site.*
import zio.*

import java.nio.file.Path

/** Docs-as-tests site builder (Test classpath; `docs/specularSite`). */
object BuildSite extends DocsSite:

  def pages = Vector(Overview.doc, Usage.doc)

  override def site: SiteModel =
    val m = meta
    super.site.copy(
      summaryMarkdown = Some(
        s"""**zipx** makes the sbt build the single source of truth for GitHub Actions CI.
It introspects the real module graph and generates a dependency-ordered, affected-aware,
cached workflow — no hand-maintained YAML module lists.
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
    EarlyEffectTheme.writeLogo(out)
end BuildSite
