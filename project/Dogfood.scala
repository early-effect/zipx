import sbt.*
import sbt.Keys.*

/** Helpers for the meta-build source mirror; the projects live in dogfood.sbt. */
object Dogfood:

  /** Point Compile sources at a main-build module; keep a separate `target/` under `project/meta-*`. */
  def mirrorMainScala(moduleDir: String): Seq[Setting[?]] = Seq(
    Compile / unmanagedSourceDirectories := {
      val repo = (LocalRootProject / baseDirectory).value.getParentFile
      Seq(repo / "modules" / moduleDir / "src" / "main" / "scala")
    },
    Compile / unmanagedResourceDirectories := Nil,
  )

end Dogfood
