import sbt.*
import sbt.Keys.*
import sbt.complete.DefaultParsers.*

object Fixture:
  private val names = (Space ~> StringBasic).+

  /** `<artifact> <excluded>...`: in `project`'s published POM, the dependency on `<artifact>` excludes exactly those
    * modules. Name none to assert it excludes nothing.
    */
  def excludes(key: InputKey[Unit], project: ProjectReference): Seq[Setting[?]] = Seq(
    key / aggregate := false,
    key := {
      val (artifact, expected) = names.parsed match
        case head +: tail => (head, tail.toSet)
        case _            => sys.error("excludes <artifact> <excluded>...")
      val pom        = fileConverter.value.toPath((project / makePom).value).toFile
      val dependency = (scala.xml.XML.loadFile(pom) \ "dependencies" \ "dependency")
        .filter(dependency => (dependency \ "artifactId").text == artifact)
      val excluded = dependency
        .flatMap(_ \ "exclusions" \ "exclusion")
        .map(exclusion => (exclusion \ "artifactId").text)
        .toSet
      assert(dependency.nonEmpty, s"the POM has no dependency on $artifact")
      assert(excluded == expected, s"$artifact excludes $excluded, expected $expected")
    },
  )
end Fixture
