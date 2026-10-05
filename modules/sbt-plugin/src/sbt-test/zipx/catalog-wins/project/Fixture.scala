import sbt.*
import sbt.Keys.*
import sbt.complete.DefaultParsers.*

object Fixture:
  val Group = "com.example.cw"

  private val selection = (Space ~> StringBasic) ~ (Space ~> StringBasic)

  /** `<artifact> <revision>`: `consumer`'s compile graph selected the module resolution names `<artifact>` at exactly
    * `<revision>`.
    */
  def selects(key: InputKey[Unit], consumer: ProjectReference): Seq[Setting[?]] = Seq(
    key / aggregate := false,
    key := {
      val (artifact, revision) = selection.parsed
      val selected             = (consumer / updateFull).value.configurations
        .filter(_.configuration.name == Compile.name)
        .flatMap(_.details)
        .filter(report => report.organization == Group && report.name == artifact)
        .flatMap(_.modules)
        .filterNot(_.evicted)
        .map(_.module.revision)
        .distinct
      assert(selected == Vector(revision), s"$artifact: selected $selected, expected $revision")
    },
  )
end Fixture
