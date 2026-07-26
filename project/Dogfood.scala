import sbt.*
import sbt.Keys.*

/** Helpers for the meta-build source mirror (projects live in dogfood.sbt).
  *
  * Visible to project/ .sbt files via unmanagedSources in project/project/build.sbt (same pattern as Dependencies).
  */
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
