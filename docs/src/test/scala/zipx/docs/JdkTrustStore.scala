package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zio.test.*

object JdkTrustStore extends DocSpecSuite:

  def doc = page("JDK trust store")(
    md"""
CVE-2026-91403 is eight compromised intermediate CAs in the JDK trust store. A hand-copied fingerprint list misses
the re-encodings JDK-8395142 generates, so zipx does not append fingerprints and does not run `keytool -delete`.

The check is a version floor, and the build number is per vendor. Zulu `25.0.5+8` is the fixed build. Temurin
`25.0.5+8` is the October CPU, built from the OpenJDK tag that does not contain the fix. The same digits are not the
same binary. Oracle and other OpenJDK builds are fixed at the extra component (`25.0.5.0.2`, `21.0.13.0.2`). A major
below 21 is refused for every vendor. A major with no line (22, 23, 24, 26) is refused. JDK 27 is safe only at
`27.0.1.0.2`.

| Vendor | JDK 21 | JDK 25 |
| --- | --- | --- |
| Zulu | `21.0.13+9` | `25.0.5+8` |
| OpenJDK | `21.0.13.0.2` | `25.0.5.0.2` |
| Temurin | no fixed build | no fixed build |
""",
    section("What CI installs")(
      md"""
Generated sbt jobs stay on the Ubuntu runner. `actions/setup-java` uses distribution `zulu` and `java-version` from
`zipxJavaVersion` (a major, default 21). That installs the current Zulu build of that major. The optional
`zipxSbtImage` path still pulls an `eclipse-temurin` sbt image. Loading the plugin on that image fails the floor
until the pin is a fixed build.

A module's `dockerBaseImage` is the image you publish. It is not the JDK that runs sbt.
"""
    ),
    section("What the plugin checks")(
      md"""
Loading the plugin reads `java.vendor` and `Runtime.version()`. Azul or Zulu in the vendor string uses the Zulu
column. Adoptium or Temurin uses the Temurin column. Anything else, including Oracle and Homebrew, uses the OpenJDK
column. A verdict other than a fixed build stops the session before compile, update, or generate. There is no skip
flag.
""",
      exampleValue {
        List(
          JdkFloor.assess(JdkFloor.Vendor.Zulu, "25.0.5+8").message,
          JdkFloor.assess(JdkFloor.Vendor.Temurin, "25.0.5+8").message,
          JdkFloor.assess(JdkFloor.Vendor.Zulu, "17.0.21+9").message,
          JdkFloor.assess(JdkFloor.Vendor.OpenJdk, "25.0.5.0.2").message,
        ).mkString("\n")
      }.assert { text =>
        assertTrue(
          text.startsWith("zipx: JDK is a fixed build\n"),
          text.contains("zipx: Temurin JDK 25.0.5+8 is not a fixed build"),
          text.contains("zipx: JDK 17 is below the minimum 21"),
          text.endsWith("zipx: JDK is a fixed build"),
        )
      },
    ),
  )
end JdkTrustStore
