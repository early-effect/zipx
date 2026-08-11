ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "zipx.it.fixture"

val wipeItCaches = taskKey[Unit](
  "Wipe project targets and the sbt action cache under HOME so the next compile must hit the remote cache"
)

val itStamp = taskKey[Unit]("Emit ZIPX_IT_STAMP <epochMs> for FixtureRunner phase timing")

val writeItCacheVersion111 = taskKey[Unit](
  "Write .it-cache-version=111; reload afterward so Global / cacheVersion re-reads it at load time"
)

val writeItCacheVersion222 = taskKey[Unit](
  "Write .it-cache-version=222; reload afterward so Global / cacheVersion re-reads it at load time"
)

lazy val root = (project in file("."))
  .settings(
    name                                   := "remote-cache-fixture",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test,
    // sbt 2 caches task outputs by default; these are side-effecting and must always run.
    itStamp := Def.uncached {
      streams.value.log.info(s"ZIPX_IT_STAMP ${System.currentTimeMillis()}")
    },
    wipeItCaches := Def.uncached {
      val log = streams.value.log
      log.info("ZIPX_IT_WIPE")
      IO.delete(target.value)
      IO.delete(baseDirectory.value / "project" / "target")
      sys.env.get("HOME").foreach { h =>
        IO.delete(file(h) / ".cache" / "sbt")
      }
    },
    writeItCacheVersion111 := Def.uncached {
      IO.write(baseDirectory.value / ".it-cache-version", "111")
      streams.value.log.info("ZIPX_IT_CACHE_VERSION 111")
    },
    writeItCacheVersion222 := Def.uncached {
      IO.write(baseDirectory.value / ".it-cache-version", "222")
      streams.value.log.info("ZIPX_IT_CACHE_VERSION 222")
    },
  )
