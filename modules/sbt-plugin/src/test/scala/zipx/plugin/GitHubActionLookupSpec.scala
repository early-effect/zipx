package zipx.plugin

import zipx.core.GitHubActionMeta
import zio.test.*

object GitHubActionLookupSpec extends ZIOSpecDefault:

  private val sha = "3d3c42e5aac5ba805825da76410c181273ba90b1"

  def spec = suite("GitHubActionLookup")(
    test("latest peels a non-draft release via injected fetch") {
      val releases =
        s"""[{"tag_name":"v7.0.2","draft":false,"prerelease":false}]"""
      val ref = s"""{"object":{"sha":"$sha","type":"commit"}}"""
      val gh  = GitHubActionLookup { url =>
        if url.endsWith("/releases") then Right(releases)
        else if url.contains("/git/ref/tags/") then Right(ref)
        else Left(s"unexpected $url")
      }
      assertTrue(
        gh.latest("actions/checkout") == Right(Some(GitHubActionMeta.Release("v7.0.2", Some(sha))))
      )
    },
    test("falls back to tags when there are no releases") {
      val tags =
        s"""[{"name":"v7.0.1","commit":{"sha":"$sha"}}]"""
      val gh = GitHubActionLookup { url =>
        if url.endsWith("/releases") then Right("[]")
        else if url.endsWith("/tags") then Right(tags)
        else Left(s"unexpected $url")
      }
      assertTrue(
        gh.latest("actions/checkout") == Right(Some(GitHubActionMeta.Release("v7.0.1", Some(sha))))
      )
    },
  )
end GitHubActionLookupSpec
