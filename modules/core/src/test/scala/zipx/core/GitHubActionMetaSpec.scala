package zipx.core

import zio.test.*

object GitHubActionMetaSpec extends ZIOSpecDefault:

  def spec = suite("GitHubActionMeta")(
    test("pickLatestRelease skips draft and prerelease") {
      val json =
        """[
          |{"tag_name":"v8.0.0-rc1","draft":false,"prerelease":true},
          |{"tag_name":"v7.0.2","draft":true,"prerelease":false},
          |{"tag_name":"v7.0.1","draft":false,"prerelease":false}
          |]""".stripMargin
      assertTrue(GitHubActionMeta.pickLatestRelease(json) == Right(Some(GitHubActionMeta.Release("v7.0.1", None))))
    },
    test("pickLatestRelease takes the highest stable semver, not GitHub's date order") {
      val json =
        """[
          |{"tag_name":"v4.9.1","draft":false,"prerelease":false},
          |{"tag_name":"v5.7.0","draft":false,"prerelease":false},
          |{"tag_name":"v3.1.0-node20","draft":false,"prerelease":false}
          |]""".stripMargin
      assertTrue(GitHubActionMeta.pickLatestRelease(json) == Right(Some(GitHubActionMeta.Release("v5.7.0", None))))
    },
    test("peelSha reads a commit git ref") {
      val json = """{"object":{"sha":"3d3c42e5aac5ba805825da76410c181273ba90b1","type":"commit"}}"""
      assertTrue(GitHubActionMeta.peelSha(json) == Right("3d3c42e5aac5ba805825da76410c181273ba90b1"))
    },
    test("peelSha peels an annotated tag") {
      val ref = """{"object":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","type":"tag"}}"""
      val obj =
        """{"object":{"sha":"3d3c42e5aac5ba805825da76410c181273ba90b1","type":"commit"}}"""
      assertTrue(GitHubActionMeta.peelSha(ref, Some(obj)) == Right("3d3c42e5aac5ba805825da76410c181273ba90b1"))
    },
    test("peelSha refuses a non-sha") {
      val json = """{"object":{"sha":"v7.0.1","type":"commit"}}"""
      GitHubActionMeta.peelSha(json) match
        case Left(err) => assertTrue(err.contains("40-hex"))
        case Right(_)  => assertTrue(false)
    },
    test("pickLatestTag skips pre-release-looking names") {
      val json =
        """[
          |{"name":"v8.0.0-rc1","commit":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}},
          |{"name":"v7.0.1","commit":{"sha":"3d3c42e5aac5ba805825da76410c181273ba90b1"}}
          |]""".stripMargin
      assertTrue(
        GitHubActionMeta.pickLatestTag(json) ==
          Right(Some(GitHubActionMeta.Release("v7.0.1", Some("3d3c42e5aac5ba805825da76410c181273ba90b1"))))
      )
    },
  )
end GitHubActionMetaSpec
