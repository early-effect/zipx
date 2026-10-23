package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zio.test.*

/** Why the toolchain image and the load-time JDK check exist, and what a build changes to adopt them. */
object JdkTrustStore extends DocSpecSuite:

  def doc = page("JDK trust store")(
    md"""
The October 2026 OpenJDK update shipped a JDK trust store that still accepts certificates from compromised
intermediate CAs ([CVE-2026-91403](https://www.scala-lang.org/blog/jdk-certificate-vulnerability.html)). A build that
runs on that JDK, or in CI on that JDK, will trust a certificate an attacker can issue. zipx refuses both. The check
is a version floor. It is the same predicate for the laptop that loads the plugin and for the image CI runs.

`dockerBaseImage` is a different image. This page does not change it.
""",
    section("Why a version, not a fingerprint list")(
      md"""
The certificates are in `cacerts`. Distrusting them means a `blocked.certs` that lists every encoding of each
certificate. JDK-8395142 generates that list. Two encodings of one certificate both validate and hash differently: an
ECDSA signature with `s` replaced by `n` minus `s`, and an RSA algorithm identifier with the NULL parameter present in
one and absent in the other. A fingerprint list copied by hand records the encoding someone published. The other
encoding still chains.

Deleting the roots (`emSign ECC Root CA - G3` and `AAA Certificate Services`) also stops that. It distrusts every
certificate that still chains only to the Sectigo root. The upstream fix leaves the roots in the store and regenerates
the blocklist. zipx follows that fix. The image does not run `keytool`, and it does not append lines to `blocked.certs`.

A `+build` number is not a shared clock. Zulu's fixed JDK 25 is `25.0.5+8`. Temurin's `25.0.5+8` is the October CPU,
the vulnerable build. OpenJDK, Oracle, Wolfi, and any vendor that is neither Azul nor Adoptium are safe on the extra
component (`25.0.5.0.2`, `21.0.13.0.2`) or on a later update (`25.0.6`). That extra component is safe for every vendor,
including Temurin and Zulu, because it is the upstream respin. Temurin has no `+build` that counts. Zulu's `+build`
does not transfer to anyone else.

| Vendor | 21 | 25 | 27 (the running JVM only) |
|---|---|---|---|
| Zulu | `21.0.13+9` | `25.0.5+8` | no `+build` counts |
| OpenJDK, Wolfi, Oracle, anyone else | `21.0.13.0.2` | `25.0.5.0.2` | `27.0.1.0.2` |
| Temurin | no `+build` counts | no `+build` counts | no `+build` counts |

A later update than the vulnerable one is safe on every vendor. Bare `25.0.5` is not. A major below 21 is refused
before the vendor check, including a fixed `17.0.21.0.2`. sbt 2 can start on 17. A zipx build does not. JDK 27 has a
live-JVM line and no pinned Wolfi package, so `zipxJavaVersion := JdkVersion("27")` fails at generate.
""",
      exampleValue {
        def show(verdict: JdkFloor.Verdict): String = verdict match
          case JdkFloor.Verdict.Safe => "safe"
          case other                 => other.message
        List(
          show(JdkFloor.assess(JdkFloor.Vendor.Zulu, "25.0.5+8")),
          show(JdkFloor.assess(JdkFloor.Vendor.Temurin, "25.0.5+8")),
          show(JdkFloor.assess(JdkFloor.Vendor.OpenJdk, "25.0.5.0.2")),
          show(JdkFloor.assess(JdkFloor.Vendor.OpenJdk, "25.0.5")),
          show(JdkFloor.assess(JdkFloor.Vendor.Zulu, "21.0.13+9")),
          show(JdkFloor.assess(JdkFloor.Vendor.Zulu, "21.0.13+8")),
          show(JdkFloor.assess(JdkFloor.Vendor.OpenJdk, "17.0.21.0.2")),
          show(JdkFloor.assess(JdkFloor.Vendor.OpenJdk, "25.0.6")),
          show(JdkFloor.assess(JdkFloor.Vendor.Temurin, "25.0.6")),
          show(JdkFloor.assess(JdkFloor.Vendor.OpenJdk, "22.0.1")),
        ).mkString("\n")
      }.assert { text =>
        assertTrue(
          text.contains("zipx: Temurin JDK 25.0.5+8 is not a fixed build"),
          text.contains("zipx: OpenJdk JDK 25.0.5 is not a fixed build"),
          text.contains("zipx: Zulu JDK 21.0.13+8 is not a fixed build"),
          text.contains("zipx: JDK 17 is below the minimum 21"),
          text.contains("zipx: JDK 22 has no fixed-build line"),
          !text.contains("zipx: Zulu JDK 25.0.5+8"),
          !text.contains("zipx: Zulu JDK 21.0.13+9"),
          !text.contains("zipx: OpenJdk JDK 25.0.5.0.2"),
          !text.contains("zipx: OpenJdk JDK 25.0.6"),
          !text.contains("zipx: Temurin JDK 25.0.6"),
        )
      },
    ),
    section("What the plugin checks")(
      md"""
Loading the plugin reads `java.vendor` and `Runtime.version()`. Azul or Zulu in the vendor string uses the Zulu column.
Adoptium or Temurin uses the Temurin column. Anything else, including Wolfi and Oracle, uses the OpenJDK column. A
verdict other than a fixed build stops the session before compile, update, or generate. There is no skip flag. The
message names the vendor and the version that is actually running.

CI does not install a JDK onto the runner. `zipxWorkflowGenerate` writes `.github/zipx-env/Dockerfile`: public
`cgr.dev/chainguard/wolfi-base` at a digest, plus the Wolfi apk `openjdk-<major>` at or above the floor. The majors
with a package are 21 (`openjdk-21=21.0.13.0.2-r0`) and 25 (`openjdk-25=25.0.5.0.2-r0`), each with the matching
`openjdk-<major>-default-jdk`. A `zipx-env-image` job publishes
`ghcr.io/<owner>/<repo>/zipx-env:df-<hash of the Dockerfile>` when that tag is missing. Every sbt job sets `container`
to that image. `macos-*` and `windows-*` runners are refused when a job runs sbt. The default stays `ubuntu-latest`.

The image is not `cgr.dev/chainguard/jdk` and not `sbtscala/scala-sbt:eclipse-temurin-*`. The cache composite does not
run `actions/setup-java`, `sbt/setup-sbt`, or `coursier/setup-action`. Those Action rows stay in the catalog so an
existing `Action` val still typechecks. sbt jobs do not run them. See **Action pins** and **Caching**.

A `workflow_call` job, including `ZipxDocs.pages`, runs the called workflow. That workflow chooses its own JDK. The
toolchain image does not wrap the call. The laptop check still applies when you load this plugin.
""",
      exampleValue {
        SbtImage
          .resolve(
            JdkVersion("21"),
            SbtImage.DefaultSbtVersion,
            Nil,
            Nil,
            coursier = false,
            docker = false,
          )
          .fold(_.message, _.dockerfile)
      }.assert { body =>
        assertTrue(
          body.contains("FROM cgr.dev/chainguard/wolfi-base@sha256:"),
          body.contains("openjdk-21=21.0.13.0.2-r0"),
          body.contains("apk add --no-cache"),
          !body.contains("eclipse-temurin"),
          !body.contains("setup-java"),
          !body.contains("keytool"),
          !body.contains("blocked.certs"),
          !body.contains("apt-get"),
        )
      },
    ),
    section("Apply it")(
      md"""
1. Run local sbt on a fixed JDK from the table. `java -version` and the vendor string are what the plugin reads. Until
   that JDK is fixed, sbt stops at load.
2. Keep `zipxJavaVersion` a major. The default is 21. `zipxJavaVersion := JdkVersion("25")` selects the 25 package.
   `JdkVersion("17")` fails at generate.
3. Version updates are on by default, and the image is where `cs launch` runs. Add
   `Pin("sbt-image", "cs", "<x.y.z>")` or generate refuses. The plugin does not invent that pin.
4. A job with `testAffected(onPush = true)` calls `gh`. Add `Pin("sbt-image", "gh", "<x.y.z>")` or generate refuses.
5. Regenerate and commit the workflow, the composites, and the image:

```
sbt zipxWorkflowGenerate
git add .github/workflows/ci.yml .github/actions/ .github/zipx-env/
```

The Dockerfile hash is the image tag. Changing a pin changes the file, the tag, and the next CI build of the image.
A cloud agent that uses `zipxAgentEnv` is the same image plus its daemon. Commit the agent files generate writes
(`.cursor/Dockerfile`, `.github/zipx-agent/install.sh`).

Defaults live in the plugin. A catalog pin overrides one digest or one apk and still has to pass the floor:

```scala
Pin("sbt-image", "wolfi-base", "sha256:<64 hex chars>")
Pin("sbt-image", "openjdk-21", "21.0.13.0.2-r0")
Pin("sbt-image", "openjdk-25", "25.0.5.0.2-r0")
Pin("sbt-image", "bash", "5.3-r14")
Pin("sbt-image", "git", "2.56.0-r3")
Pin("sbt-image", "curl", "8.23.0-r2")
```

Pinning `openjdk-21` while the major is 25 leaves that pin unused. Pinning `openjdk-25` to a 21 apk while the major is
25 is a mismatch, and generate fails. A pin of `sbtscala/scala-sbt`, or any pin whose text contains `eclipse-temurin`, is
refused. Ubuntu agent pins (`agent-base`, `agent-docker`, `agent-sbt`) are unused while sbt jobs run in this image.
Generate warns. Delete them.

The pin companion reads the wolfi-base digest and the Wolfi apk index. It stays on the major `zipxJavaVersion` names,
and it will not move a pin below the floor.
""",
      exampleValue {
        def pinned(id: String, version: String): Pin =
          Pin(PinFeedName.unsafeMake("sbt-image"), id, DepVersion.unsafeMake(version), None, None)
        def message(java: String, pins: Pin*): String =
          SbtImage
            .resolve(
              JdkVersion.unsafeMake(java),
              SbtImage.DefaultSbtVersion,
              pins.toSeq,
              Nil,
              coursier = false,
              docker = false,
            )
            .fold(_.message, _ => "planned")
        List(
          message("21", pinned("openjdk-21", "21.0.13-r0")),
          message("25", pinned("openjdk-25", "25.0.5-r1")),
          message("21", pinned("sbtscala/scala-sbt", "1")),
          message("25", pinned("openjdk-25", "21.0.13.0.2-r0")),
          message("27"),
          message("17"),
        ).mkString("\n")
      }.assert { text =>
        assertTrue(
          text.contains("zipx: openjdk pin '21.0.13-r0' is below the fixed build 21.0.13.0.2"),
          text.contains("zipx: openjdk pin '25.0.5-r1' is below the fixed build 25.0.5.0.2"),
          text.contains("zipx: Temurin image pin 'sbtscala/scala-sbt=1' is retired. The toolchain image is Wolfi."),
          text.contains("zipx: openjdk pin is major 21 and zipxJavaVersion is 25"),
          text.contains("zipx: JDK 27 has no pinned Wolfi package"),
          text.contains("zipx: JDK 17 is below the minimum 21"),
        )
      },
    ),
    section("What you still choose")(
      md"""
`dockerBaseImage` is the image your service runs in after `Docker/publish`. zipx does not rewrite it. An
`eclipse-temurin:21-jre` base in a `DockerPlugin` module stays that base. If that runtime must also distrust the
compromised CAs, pick a base that does. The toolchain image is the JDK that compiles the build.

A remote-cache integration fixture may still pull a published sbt image by name. That pull is the fixture, not the
toolchain, and it is not a JDK zipx will run sbt on.
"""
    ),
  )
end JdkTrustStore
