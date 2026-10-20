package zipx.core

/** One pinned tool appended to the derived sbt image.
  *
  * zipx writes `FROM`, the built-in tools, every `RUN`, `ENV`, and `WORKDIR`. The function closes over build state and
  * returns data. It is called at generate for the catalog pin and both CPU arches. It does not run in the image build
  * and it does not hit the network. [[lookup]] is only for the pin companion.
  *
  * Paths are absolute and stay off [[SbtImage.CacheHome]], which the LocalDir snapshot mounts over. `ENV` is written
  * into the derived image and onto each sbt job. The cloud agent copies the plain keys when it builds a Dockerfile.
  */
final case class SbtImageLayer(
    id: String,
    lookup: PinLookup,
    steps: (String, CpuArch) => List[ImageStep],
    env: Map[String, String],
):
  def withEnv(entries: (String, String)*): SbtImageLayer =
    copy(env = env ++ entries)

object SbtImageLayer:

  /** `install`'s function already knows `(version, arch)`. The lambda does not need ascription. */
  def apply(id: String, lookup: PinLookup): Install = Install(id, lookup)

  final class Install(id: String, lookup: PinLookup):
    def install(steps: (String, CpuArch) => List[ImageStep]): SbtImageLayer =
      SbtImageLayer(id, lookup, steps, Map.empty)

  private[zipx] final case class Rendered(runs: List[String], env: Map[String, String])

  private final case class LayerOut(id: String, runs: List[String], env: Map[String, String])

  private[zipx] def assemble(pins: Seq[Pin], layers: Seq[SbtImageLayer]): Either[SbtImageError, Rendered] =
    for
      _     <- ids(layers)
      parts <- each(layers)(layer => one(pins, layer))
      _     <- orphans(pins, layers)
      env   <- mergeEnv(parts)
    yield Rendered(parts.flatMap(_.runs), env)

  private def ids(layers: Seq[SbtImageLayer]): Either[SbtImageError, Unit] =
    layers
      .foldLeft[Either[SbtImageError, Set[String]]](Right(Set.empty)) { (acc, layer) =>
        acc.flatMap { seen =>
          layerId(layer.id).flatMap { id =>
            if seen.contains(id) then Left(SbtImageError.DuplicateLayerId(id))
            else Right(seen + id)
          }
        }
      }
      .map(_ => ())

  private def layerId(id: String): Either[SbtImageError, String] =
    if reserved(id) then Left(SbtImageError.ReservedLayerId(id))
    else if !id.matches("[A-Za-z][A-Za-z0-9._-]*") then Left(SbtImageError.BadLayerId(id))
    else Right(id)

  private def reserved(id: String): Boolean =
    id == SbtImage.NodeId || id == SbtImage.CsId || id == SbtImage.DockerCliId

  private def one(pins: Seq[Pin], layer: SbtImageLayer): Either[SbtImageError, LayerOut] =
    for
      version <- pinOf(pins, layer.id)
      checked <- checkEnv(layer)
      runs    <- runsOf(layer, version)
    yield LayerOut(layer.id, runs, checked)

  private def pinOf(pins: Seq[Pin], id: String): Either[SbtImageError, String] =
    pins.find(pin => pin.feed == SbtImage.Feed && pin.id == id).map(_.current) match
      case None                                          => Left(SbtImageError.MissingLayerPin(id))
      case Some(version) if SbtImage.isConcrete(version) => Right(version)
      case Some(version)                                 => Left(SbtImageError.LayerPinNotConcrete(id, version))

  private def orphans(pins: Seq[Pin], layers: Seq[SbtImageLayer]): Either[SbtImageError, Unit] =
    val known   = Set(SbtImage.ImageId, SbtImage.NodeId, SbtImage.CsId, SbtImage.DockerCliId) ++ layers.map(_.id)
    val unknown = pins.filter(_.feed == SbtImage.Feed).map(_.id).filterNot(known.contains)
    unknown.headOption match
      case None     => Right(())
      case Some(id) => Left(SbtImageError.OrphanPin(id))

  private def checkEnv(layer: SbtImageLayer): Either[SbtImageError, Map[String, String]] =
    layer.env.foldLeft[Either[SbtImageError, Map[String, String]]](Right(Map.empty)) { case (acc, (key, value)) =>
      acc.flatMap { got =>
        if owned(key) then Left(SbtImageError.OwnedEnvKey(layer.id, key))
        else if !key.matches("[A-Za-z_][A-Za-z0-9_]*") then Left(SbtImageError.BadEnvKey(layer.id, key))
        else if badValue(value) then Left(SbtImageError.BadEnvValue(layer.id, key))
        else Right(got + (key -> value))
      }
    }

  private val Owned = Set("HOME", "JAVA_OPTS", "SBT_OPTS", "COURSIER_TTL", "GITHUB_TOKEN")

  private def owned(key: String): Boolean =
    Owned.contains(key) || key.startsWith("GIT_CONFIG_") || key.startsWith("GITHUB_")

  private def badValue(value: String): Boolean =
    value.contains('$') || value.contains('\n') || value.contains('\r') || value.contains("${{") ||
      value.contains('"') || value.contains('\\') || value.contains('`') ||
      value == SbtImage.CacheHome || value.startsWith(SbtImage.CacheHome + "/") ||
      value == "/target" || value.startsWith("target/") || value == "target" || value.contains("/target/")

  private def runsOf(layer: SbtImageLayer, version: String): Either[SbtImageError, List[String]] =
    val amd = layer.steps(version, CpuArch.Amd64)
    val arm = layer.steps(version, CpuArch.Arm64)
    if amd.isEmpty && arm.isEmpty then Left(SbtImageError.EmptyLayer(layer.id))
    else if amd.length != arm.length then
      Left(SbtImageError.ArchMismatch(layer.id, "the arches return different steps"))
    else
      for
        pairs <- each(amd.zip(arm).zipWithIndex.toList) { case ((left, right), index) =>
          pair(layer.id, index, left, right)
        }
        runs <- stitch(layer.id, pairs)
      yield runs
    end if
  end runsOf

  private def pair(id: String, index: Int, left: ImageStep, right: ImageStep): Either[SbtImageError, StepPair] =
    (left, right) match
      case (ImageStep.File(urlL, dest, source, mode), ImageStep.File(urlR, destR, sourceR, modeR))
          if dest == destR && source == sourceR && mode == modeR =>
        for
          _ <- https(id, urlL)
          _ <- https(id, urlR)
          _ <- destOf(id, dest)
          _ <- sourceOf(id, source)
        yield StepPair.Fetch(urlL, urlR, dest, source, mode)
      case (ImageStep.Tree(urlL, dest, format, strip), ImageStep.Tree(urlR, destR, formatR, stripR))
          if dest == destR && format == formatR && strip == stripR =>
        for
          _ <- https(id, urlL)
          _ <- https(id, urlR)
          _ <- destOf(id, dest)
          _ <- stripOf(id, format, strip)
        yield StepPair.Extract(urlL, urlR, dest, format, strip)
      case (ImageStep.EmptyFile(dest), ImageStep.EmptyFile(destR)) if dest == destR =>
        destOf(id, dest).map(_ => StepPair.Marker(dest))
      case (ImageStep.Apt(name, verL), ImageStep.Apt(nameR, verR)) if name == nameR =>
        for
          _ <- apt(id, name, verL)
          _ <- apt(id, name, verR)
        yield StepPair.Package(name, verL, verR)
      case _ =>
        Left(SbtImageError.ArchMismatch(id, s"step $index differs by arch in more than the URL or package version"))

  private def stitch(id: String, steps: List[StepPair]): Either[SbtImageError, List[String]] =
    val packages = steps.collect { case pkg: StepPair.Package => pkg }
    val aptRun   = Option.when(packages.nonEmpty)(aptScript(packages))
    def loop(rest: List[StepPair], aptDone: Boolean, acc: List[String]): List[String] =
      rest match
        case Nil                           => acc.reverse
        case (_: StepPair.Package) :: tail =>
          aptRun match
            case Some(run) if !aptDone => loop(tail, true, run :: acc)
            case _                     => loop(tail, true, acc)
        case other :: tail => loop(tail, aptDone, script(other) :: acc)
    val runs = loop(steps, false, Nil)
    if runs.isEmpty then Left(SbtImageError.EmptyLayer(id)) else Right(runs)
  end stitch

  private def https(id: String, url: String): Either[SbtImageError, Unit] =
    val bad =
      !url.startsWith("https://") || url.contains("$") || url.contains("`") || url.contains("'") ||
        url.contains("\"") || url.contains("${{") || url.exists(_.isWhitespace)
    Either.cond(!bad, (), SbtImageError.BadUrl(id, url))

  private def destOf(id: String, dest: String): Either[SbtImageError, Unit] =
    val bad =
      dest.isEmpty || !dest.startsWith("/") || dest.contains("..") || dest.contains("$") || dest.contains("'") ||
        dest.contains("\"") || dest.exists(_.isWhitespace) || under(dest, "/tmp") || under(dest, "/root") ||
        under(dest, SbtImage.CacheHome)
    Either.cond(!bad, (), SbtImageError.BadDest(id, dest))

  private def under(path: String, root: String): Boolean =
    path == root || path.startsWith(root + "/")

  private def memberMeta(ch: Char): Boolean =
    ch == '$' || ch == '`' || ch == '"' || ch == '\'' || ch == '\\' || ch == '*' || ch == '?' ||
      ch == '[' || ch == ']' || ch == '{' || ch == '}' || ch == '~' || ch == ';' || ch == '|' ||
      ch == '&' || ch == '<' || ch == '>'

  private def sourceOf(id: String, source: FileSource): Either[SbtImageError, Unit] =
    source match
      case FileSource.Member(_, path) =>
        val parts = path.split("/")
        val bad   =
          path.isEmpty || path.startsWith("/") || parts.exists(part => part.isEmpty || part == "." || part == "..") ||
            path.exists(ch => ch.isWhitespace || memberMeta(ch))
        Either.cond(!bad, (), SbtImageError.BadMember(id, path))
      case FileSource.Raw | FileSource.Gzip => Right(())

  private def stripOf(id: String, format: ArchiveFormat, strip: Int): Either[SbtImageError, Unit] =
    if strip < 0 then Left(SbtImageError.BadStrip(id, strip))
    else if format == ArchiveFormat.Zip && strip != 0 then Left(SbtImageError.BadStrip(id, strip))
    else Right(())

  private val AptName = "[a-z0-9][a-z0-9+.-]*"

  private val AptVersion = "[A-Za-z0-9.+:~-]+"

  private def apt(id: String, name: String, version: String): Either[SbtImageError, Unit] =
    val bad =
      !name.matches(AptName) || !version.matches(AptVersion) || version.equalsIgnoreCase("latest")
    Either.cond(!bad, (), SbtImageError.BadApt(id, name, version))

  private def mergeEnv(parts: List[LayerOut]): Either[SbtImageError, Map[String, String]] =
    parts
      .foldLeft[Either[SbtImageError, (Map[String, String], Map[String, String])]](Right((Map.empty, Map.empty))) {
        (acc, part) =>
          acc.flatMap { (env, owners) =>
            part.env.foldLeft[Either[SbtImageError, (Map[String, String], Map[String, String])]](Right((env, owners))) {
              case (inner, (key, value)) =>
                inner.flatMap { (got, who) =>
                  who.get(key) match
                    case Some(first) => Left(SbtImageError.DuplicateEnvKey(key, first, part.id))
                    case None        => Right((got + (key -> value), who + (key -> part.id)))
                }
            }
          }
      }
      .map(_._1)

  private def each[A, B](values: Seq[A])(f: A => Either[SbtImageError, B]): Either[SbtImageError, List[B]] =
    values.foldLeft[Either[SbtImageError, List[B]]](Right(Nil)) { (acc, value) =>
      acc.flatMap(done => f(value).map(done :+ _))
    }

  private enum StepPair:
    case Fetch(amd: String, arm: String, dest: String, source: FileSource, mode: FileMode)
    case Extract(amd: String, arm: String, dest: String, format: ArchiveFormat, strip: Int)
    case Marker(dest: String)
    case Package(name: String, amd: String, arm: String)

  private def script(step: StepPair): String =
    step match
      case StepPair.Fetch(amd, arm, dest, source, mode)    => fetch(amd, arm, dest, source, mode)
      case StepPair.Extract(amd, arm, dest, format, strip) => extract(amd, arm, dest, format, strip)
      case StepPair.Marker(dest)                           =>
        run(List(s"mkdir -p -- ${q(parent(dest))}", s": > ${q(dest)}"))
      case pkg: StepPair.Package => aptScript(List(pkg))

  /** `apt-get update` is not a function of this text. The published tag is the text hash, and the push is skipped when
    * that tag already exists, so the first build is the one that sticks.
    */
  private def aptScript(packages: List[StepPair.Package]): String =
    val same    = packages.forall(pkg => pkg.amd == pkg.arm)
    val install =
      if same then packages.map(pkg => q(s"${pkg.name}=${pkg.amd}")).mkString(" ")
      else "$pkgs"
    val choose =
      if same then Nil
      else
        val amd = packages.map(pkg => s"${pkg.name}=${pkg.amd}").mkString(" ")
        val arm = packages.map(pkg => s"${pkg.name}=${pkg.arm}").mkString(" ")
        List(
          """case "$(uname -m)" in""",
          s"  x86_64) pkgs=${q(amd)} ;;",
          s"  aarch64) pkgs=${q(arm)} ;;",
          """  *) echo "zipx: unsupported architecture $(uname -m)" >&2; exit 1 ;;""",
          "esac",
        )
    run(
      choose ++ List(
        "set -f",
        "apt-get update",
        s"apt-get install -y --no-install-recommends $install",
        "set +f",
        "rm -rf /var/lib/apt/lists/*",
      )
    )
  end aptScript

  private def fetch(amd: String, arm: String, dest: String, source: FileSource, mode: FileMode): String =
    val (choose, url) = archUrl(amd, arm)
    val file          = "/tmp/zipx-layer"
    val place         = source match
      case FileSource.Raw =>
        List(
          s"mkdir -p -- ${q(parent(dest))}",
          s"curl -fsSL -o ${q(dest)} $url",
          s"chmod ${mode.bits} ${q(dest)}",
        )
      case FileSource.Gzip =>
        List(
          s"mkdir -p -- ${q(parent(dest))}",
          s"curl -fsSL -o $file $url",
          s"gzip -d -c $file > ${q(dest)}",
          s"chmod ${mode.bits} ${q(dest)}",
          s"rm -f $file",
        )
      case FileSource.Member(format, path) =>
        val unpack = format match
          case ArchiveFormat.TarGz => s"tar -xzf $file -C $file.d ${q(path)}"
          case ArchiveFormat.TarXz => s"tar -xJf $file -C $file.d ${q(path)}"
          case ArchiveFormat.Zip   => s"( cd $file.d && jar xf $file ${q(path)} )"
        List(
          s"rm -rf $file.d",
          s"mkdir -p -- $file.d",
          s"curl -fsSL -o $file $url",
          unpack,
          s"mkdir -p -- ${q(parent(dest))}",
          s"mv ${q(s"$file.d/$path")} ${q(dest)}",
          s"chmod ${mode.bits} ${q(dest)}",
          s"rm -rf $file $file.d",
        )
    run(choose ++ place)
  end fetch

  private def extract(amd: String, arm: String, dest: String, format: ArchiveFormat, strip: Int): String =
    val (choose, url) = archUrl(amd, arm)
    val file          = "/tmp/zipx-layer"
    val unpack        = format match
      case ArchiveFormat.TarGz => s"tar -xzf $file -C ${q(dest)} --strip-components=$strip"
      case ArchiveFormat.TarXz => s"tar -xJf $file -C ${q(dest)} --strip-components=$strip"
      case ArchiveFormat.Zip   => s"( cd ${q(dest)} && jar xf $file )"
    run(
      choose ++ List(
        s"mkdir -p -- ${q(dest)}",
        s"curl -fsSL -o $file $url",
        unpack,
        s"rm -f $file",
      )
    )
  end extract

  /** Lines that set `url`, and the shell word that expands to it. One word when both arches share a URL. */
  private def archUrl(amd: String, arm: String): (List[String], String) =
    if amd == arm then (Nil, q(amd))
    else
      (
        List(
          """case "$(uname -m)" in""",
          s"  x86_64) url=${q(amd)} ;;",
          s"  aarch64) url=${q(arm)} ;;",
          """  *) echo "zipx: unsupported architecture $(uname -m)" >&2; exit 1 ;;""",
          "esac",
        ),
        "\"$url\"",
      )

  private def run(lines: List[String]): String =
    "RUN set -eux; \\\n    " + lines.mkString("; \\\n    ")

  private def q(raw: String): String = s"'$raw'"

  private def parent(path: String): String =
    val slash = path.lastIndexOf('/')
    if slash <= 0 then "/" else path.substring(0, slash)

  private[zipx] def envLine(key: String, value: String): String =
    val quoted = value.replace("\\", "\\\\").replace("\"", "\\\"")
    s"""ENV $key="$quoted""""
end SbtImageLayer

enum CpuArch:
  case Amd64, Arm64

enum FileMode:
  case Executable, Readable

  def bits: String = this match
    case Executable => "0755"
    case Readable   => "0644"

enum ArchiveFormat:
  case TarGz, TarXz, Zip

enum FileSource:
  case Raw
  case Gzip
  case Member(format: ArchiveFormat, path: String)

enum ImageStep:
  /** One downloaded file, or one archive member, at `dest`. */
  case File(url: String, dest: String, source: FileSource, mode: FileMode)

  /** An archive extracted into `dest`. `strip` is `tar --strip-components`. Zip only allows 0. The JDK `jar` tool
    * extracts zip, so the image does not need `unzip`.
    */
  case Tree(url: String, dest: String, format: ArchiveFormat, strip: Int)

  /** An empty file. Some tool layouts treat a marker as part of the install. */
  case EmptyFile(dest: String)

  /** A distro package at an exact version. zipx writes the only `apt-get` line. There is no unpinned install. */
  case Apt(name: String, version: String)
end ImageStep

enum SbtImageError:
  case LayersWhileImageOff
  case FeedShadowed
  case BadJavaVersion(raw: String)
  case MissingImagePin
  case BadImagePin(raw: String)
  case JdkMismatch(imageMajor: Int, javaVersion: String)
  case NodeMismatch(pin: String, asked: String)
  case NodePinNotConcrete(pin: String)
  case NodeAliasUnpinned(asked: String)
  case ManyNodeVersions(versions: List[String])
  case ToolPinNotConcrete(id: String, pin: String)
  case MissingToolPin(id: String, why: String)
  case BadLayerId(id: String)
  case ReservedLayerId(id: String)
  case DuplicateLayerId(id: String)
  case MissingLayerPin(id: String)
  case LayerPinNotConcrete(id: String, version: String)
  case OrphanPin(id: String)
  case EmptyLayer(id: String)
  case BadUrl(id: String, url: String)
  case BadDest(id: String, dest: String)
  case ArchMismatch(id: String, detail: String)
  case BadStrip(id: String, strip: Int)
  case BadApt(id: String, name: String, version: String)
  case BadMember(id: String, path: String)
  case OwnedEnvKey(id: String, key: String)
  case BadEnvKey(id: String, key: String)
  case BadEnvValue(id: String, key: String)
  case DuplicateEnvKey(key: String, first: String, second: String)
  case EnvClash(key: String, jobValue: String, layerValue: String)
  case BadCandidate(id: String, version: String)
  case CandidateMajor(id: String, version: String, major: Int)

  def message: String = this match
    case LayersWhileImageOff =>
      "zipx: zipxSbtImageLayers is set and zipxSbtImage is false"
    case FeedShadowed =>
      "zipx: a PinFeed named sbt-image would replace the image and layer lookups. Drop it. A layer carries its own lookup."
    case BadJavaVersion(raw) =>
      s"zipx: zipxJavaVersion '$raw' has no JDK major"
    case MissingImagePin =>
      s"""zipx: zipxSbtImage needs Pin("${SbtImage.Feed: String}", "${SbtImage.ImageId}", "eclipse-temurin-<jdk>_<build>_2.x@sha256:<digest>")"""
    case BadImagePin(raw) =>
      s"zipx: sbt image pin '$raw' must be eclipse-temurin-<jdk>_<build>_2.x@sha256:<digest>"
    case JdkMismatch(imageMajor, javaVersion) =>
      s"zipx: sbt image pin is Temurin $imageMajor and zipxJavaVersion is $javaVersion"
    case NodeMismatch(pin, asked) =>
      s"zipx: Node pin is $pin and a capability asks for $asked"
    case NodePinNotConcrete(pin) =>
      s"zipx: Node pin '$pin' is not a concrete x.y.z"
    case NodeAliasUnpinned(asked) =>
      s"""zipx: Node version '$asked' is not a concrete x.y.z. Pin("${SbtImage.Feed: String}", "${SbtImage.NodeId}", "<x.y.z>") so the pin companion can move it"""
    case ManyNodeVersions(versions) =>
      s"zipx: zipxSbtImage needs one Node version; capabilities asked for ${versions.mkString(", ")}"
    case ToolPinNotConcrete(id, pin) =>
      s"zipx: $id pin '$pin' is not a concrete x.y.z"
    case MissingToolPin(id, why) =>
      s"""zipx: zipxSbtImage with $why needs Pin("${SbtImage.Feed: String}", "$id", "<x.y.z>")"""
    case BadLayerId(id) =>
      s"zipx: layer id '$id' must start with a letter and contain only letters, digits, '.', '_', or '-'"
    case ReservedLayerId(id) =>
      s"zipx: layer id '$id' is reserved for a built-in image tool"
    case DuplicateLayerId(id) =>
      s"zipx: layer id '$id' is declared twice"
    case MissingLayerPin(id) =>
      s"""zipx: layer $id needs Pin("${SbtImage.Feed: String}", "$id", "<x.y.z>")"""
    case LayerPinNotConcrete(id, version) =>
      s"zipx: layer $id pin '$version' is not a concrete x.y.z"
    case OrphanPin(id) =>
      s"zipx: Pin feed sbt-image id '$id' has no image layer and is not a built-in tool"
    case EmptyLayer(id) =>
      s"zipx: layer $id installs nothing"
    case BadUrl(id, _) =>
      s"zipx: layer $id URL must be https and contain no shell or Actions interpolation"
    case BadDest(id, dest) =>
      s"zipx: layer $id path '$dest' must be absolute and outside /home/sbtuser, /root, and /tmp"
    case ArchMismatch(id, detail) =>
      s"zipx: layer $id arches disagree ($detail). Only the URL or apt version may differ."
    case BadStrip(id, strip) =>
      s"zipx: layer $id strip $strip is not valid for that archive"
    case BadApt(id, name, version) =>
      s"zipx: layer $id apt package '$name=$version' needs a Debian name and an exact version"
    case BadMember(id, path) =>
      s"zipx: layer $id archive member '$path' must be a relative path"
    case OwnedEnvKey(id, key) =>
      s"zipx: layer $id cannot set $key. That key belongs to the sbt image job."
    case BadEnvKey(id, key) =>
      s"zipx: layer $id env key '$key' is not an identifier"
    case BadEnvValue(id, key) =>
      s"zipx: layer $id env $key must be a plain literal, not a workspace path, a cache path, or an interpolation"
    case DuplicateEnvKey(key, first, second) =>
      s"zipx: layers $first and $second both set env $key"
    case EnvClash(key, jobValue, layerValue) =>
      s"zipx: env $key is '$jobValue' on the job and '$layerValue' on the image layer"
    case BadCandidate(id, version) =>
      s"zipx: layer $id lookup returned '$version', which is not a concrete x.y.z"
    case CandidateMajor(id, version, major) =>
      s"zipx: layer $id lookup returned '$version', which is not $major.x.y"
end SbtImageError
