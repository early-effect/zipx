import sbt.*
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser
import zipx.core.{ArtifactRegistry, GitSha, Lib, PinRewrite, ReleaseVersion, ReleaseWorkflow, SnapshotRevision}
import zipx.plugin.ZipxPlugin.autoImport.zipxVersions

/** What both repos in this fixture share: one registry, commit ids recorded by name, and the catalog edit a person
  * makes when they pin another repo's commit.
  */
object Fixture:
  val Organization: String = "com.example.ee"

  val registry: File = file("released").getAbsoluteFile

  val releaseWorkflow: ReleaseWorkflow = ReleaseWorkflow(ArtifactRegistry.Url(registry.toURI.toString))

  /** Ascent and heddle both name their snapshot repository in the build. */
  val resolve: Setting[?] = resolvers += "fixture" at registry.toURI.toString

  /** A revision a library asked for: a release, or a commit recorded under a name. */
  enum Wanted:
    case Release(version: String)
    case Commit(line: String, shaName: String)

  private val wanted: Parser[Wanted] =
    (Space ~> literal("release") ~> Space ~> StringBasic).map(Wanted.Release(_)) |
      (Space ~> literal("commit") ~> Space ~> StringBasic ~ (Space ~> StringBasic)).map { case (line, name) =>
        Wanted.Commit(line, name)
      }

  val recordSha        = inputKey[Unit]("recordSha <name>: write HEAD's sha to shas/<name>")
  val pinCommit        = inputKey[Unit]("pinCommit <artifact> <line> <name>: pin a recorded commit as the registry stores it")
  val assertInRegistry = inputKey[Unit]("assertInRegistry <artifact> release <v> | commit <line> <name>")

  /** Each task acts on the repo once. A bare setting applies to every project, so aggregation would repeat it. */
  def settings: Seq[Setting[?]] = Seq(
    recordSha / aggregate        := false,
    pinCommit / aggregate        := false,
    assertInRegistry / aggregate := false,
    recordSha := {
      val name = (Space ~> StringBasic).parsed
      val root = (LocalRootProject / baseDirectory).value
      IO.write(root / "shas" / name, git(root, "rev-parse", "HEAD"))
    },
    pinCommit := {
      val ((artifact, line), name) = (Space ~> StringBasic ~ (Space ~> StringBasic) ~ (Space ~> StringBasic)).parsed
      val root                     = (LocalRootProject / baseDirectory).value
      val catalog                  = root / "project" / "ZipxVersions.scala"
      val rows                     = zipxVersions.value
      val next                     =
        for
          lib <- rows
            .collectFirst { case lib: Lib if lib.artifact == artifact && lib.family.isEmpty => lib }
            .toRight(s"no Lib row for $artifact")
          to     <- revision(root, Wanted.Commit(line, name))
          source <- PinRewrite.replace(IO.read(catalog), lib.group, lib.artifact, lib.version, to)
        yield source
      next.fold(sys.error, IO.write(catalog, _))
    },
    assertInRegistry := {
      val (artifact, want) = ((Space ~> StringBasic) ~ wanted).parsed
      val root             = (LocalRootProject / baseDirectory).value
      revision(root, want).fold(
        sys.error,
        rev => assert(pom(organizationDir / artifact / rev).isDefined, s"no POM for $artifact $rev in $registry"),
      )
    },
  )

  /** `consumer` compiles `module` from this build, whatever revision a library asked for, and that revision is
    * evicted.
    */
  def inRepoWins(key: InputKey[Unit], consumer: ProjectReference, module: ProjectReference): Seq[Setting[?]] = Seq(
    key / aggregate := false,
    key := {
      val want     = wanted.parsed
      val root     = (LocalRootProject / baseDirectory).value
      val report   = (consumer / updateFull).value
      val id       = (module / projectID).value
      val scalaMod = (module / scalaModuleInfo).value
      val name     = CrossVersion(id, scalaMod).fold(id.name)(_(id.name))
      val reports  = report.configurations
        .filter(_.configuration.name == Compile.name)
        .flatMap(_.details)
        .filter(d => d.organization == id.organization && d.name == name)
        .flatMap(_.modules)
      val selected = reports.filterNot(_.evicted).map(_.module.revision).distinct
      val evicted  = reports.filter(_.evicted).map(_.module.revision).distinct
      revision(root, want).fold(
        sys.error,
        rev =>
          assert(selected == Vector(id.revision), s"$name: selected $selected, this build compiles ${id.revision}")
          assert(evicted.contains(rev), s"$name: evicted $evicted, expected $rev"),
      )
    },
  )

  /** The library's published POM, at the commit recorded under `<name>`, names `dependency` at the wanted revision. */
  def pomNames(key: InputKey[Unit], artifact: String, line: String, dependency: String): Seq[Setting[?]] = Seq(
    key / aggregate := false,
    key := {
      val (name, want) = ((Space ~> StringBasic) ~ wanted).parsed
      val root         = (LocalRootProject / baseDirectory).value
      val checked      =
        for
          own   <- revision(root, Wanted.Commit(line, name))
          dep   <- revision(root, want)
          file  <- pom(organizationDir / artifact / own).toRight(s"no POM for $artifact $own")
          named <- (scala.xml.XML.loadFile(file) \\ "dependency")
            .find(d => (d \ "artifactId").text == dependency)
            .map(d => (d \ "version").text)
            .toRight(s"$artifact $own does not depend on $dependency")
        yield (named, dep)
      checked.fold(sys.error, { case (named, dep) => assert(named == dep, s"$artifact names $dependency $named, not $dep") })
    },
  )

  private def organizationDir: File = Organization.split('.').foldLeft(registry)(_ / _)

  private def pom(dir: File): Option[File] =
    Option(dir.listFiles()).toList.flatten.find(_.getName.endsWith(".pom"))

  private def revision(root: File, want: Wanted): Either[String, String] =
    want match
      case Wanted.Release(version) => Right(version)
      case Wanted.Commit(line, name) =>
        val recorded = root / "shas" / name
        for
          raw        <- Option.when(recorded.exists)(IO.read(recorded).trim).toRight(s"no commit recorded as $name")
          parsedLine <- ReleaseVersion.make(line)
          full       <- GitSha.make(raw)
          stored     <- SnapshotRevision.commit(parsedLine, full).mavenRevision(releaseWorkflow.registry).left.map(_.message)
        yield stored

  private def git(root: File, args: String*): String =
    scala.sys.process.Process("git" +: args, root).!!.trim
end Fixture
