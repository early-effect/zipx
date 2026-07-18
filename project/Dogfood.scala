import sbt.*
import sbt.Keys.*

/** Helpers for the meta-build source mirror (projects live in `dogfood.sbt`).
  *
  * `project/project/Dogfood.scala` is a symlink to this file so meta-meta `.sbt` files can call it.
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
