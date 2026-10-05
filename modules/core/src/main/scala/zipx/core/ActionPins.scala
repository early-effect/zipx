package zipx.core

import neotype.unwrap
import zipx.workflow.ActionRef

/** Hash-pinned GitHub Actions used in generated workflows.
  *
  * Catalog [[Action]] rows in `project/ZipxVersions.scala` overlay [[ActionPins.Defaults]]. YAML is generate/jar
  * output, never an input. Override for one-offs with `zipxActions` in `build.sbt`:
  *
  * {{{
  * zipxActions := ActionPins.Defaults.copy(
  *   checkout = ActionRef("actions/checkout@<sha>"),
  * )
  * }}}
  *
  * @param setupNode
  *   emitted only for a capability that asks for a Node version ([[Capability.withNodeVersion]]).
  * @param cache
  *   used by [[CacheBackend.LocalDir]].
  * @param uploadArtifact
  *   Central staging share.
  * @param downloadArtifact
  *   Central staging reassembly.
  * @param versions
  *   `# vX.Y.Z` labels keyed by [[ActionPins.Field.key]]. An extra pin's label is keyed `extra.<key>`, which cannot
  *   collide because a field key has no dot.
  * @param extra
  *   pins for actions zipx does not emit itself, keyed by action name (`owner/repo`).
  */
final case class ActionPins(
    checkout: ActionRef = ActionPins.BootstrapCheckout,
    setupJava: ActionRef = ActionPins.BootstrapSetupJava,
    setupSbt: ActionRef = ActionPins.BootstrapSetupSbt,
    setupNode: ActionRef = ActionPins.BootstrapSetupNode,
    cache: ActionRef = ActionPins.BootstrapCache,
    uploadArtifact: ActionRef = ActionPins.BootstrapUploadArtifact,
    downloadArtifact: ActionRef = ActionPins.BootstrapDownloadArtifact,
    versions: Map[String, String] = Map.empty,
    extra: Map[String, ActionRef] = Map.empty,
):
  import ActionPins.Field

  def field(f: Field): ActionRef = f match
    case Field.Checkout         => checkout
    case Field.SetupJava        => setupJava
    case Field.SetupSbt         => setupSbt
    case Field.SetupNode        => setupNode
    case Field.Cache            => cache
    case Field.UploadArtifact   => uploadArtifact
    case Field.DownloadArtifact => downloadArtifact

  def withField(f: Field, ref: ActionRef): ActionPins = f match
    case Field.Checkout         => copy(checkout = ref)
    case Field.SetupJava        => copy(setupJava = ref)
    case Field.SetupSbt         => copy(setupSbt = ref)
    case Field.SetupNode        => copy(setupNode = ref)
    case Field.Cache            => copy(cache = ref)
    case Field.UploadArtifact   => copy(uploadArtifact = ref)
    case Field.DownloadArtifact => copy(downloadArtifact = ref)

  def version(f: Field): Option[String] = versions.get(f.key)

  /** Derived from [[cache]] so the restore-only half cannot drift. `unsafeMake` is safe: inserting a path segment
    * before `@` keeps the ref valid.
    */
  def cacheRestore: ActionRef =
    val (action, ref) = cache.unwrap.span(_ != '@')
    ActionRef.unsafeMake(s"$action/restore$ref")

  /** `key` is the action name (`owner/repo`), which [[extraByPrefix]] matches on. */
  def withExtra(key: String, ref: ActionRef, version: Option[String] = None): ActionPins =
    copy(
      extra = extra.updated(key, ref),
      versions = version.fold(versions - ActionPins.extraVersionKey(key))(v =>
        versions.updated(ActionPins.extraVersionKey(key), v)
      ),
    )

  def extraRef(key: String): Option[ActionRef] = extra.get(key)

  def extraVersion(key: String): Option[String] = versions.get(ActionPins.extraVersionKey(key))

  def extraByPrefix(prefix: String): Option[ActionRef] =
    extra.collectFirst {
      case (key, ref) if key == prefix || ActionPins.namesPrefix(ref.unwrap, prefix) => ref
    }

end ActionPins

object ActionPins:

  private[core] def extraVersionKey(key: String): String = s"$ExtraPrefix.$key"

  private[core] val ExtraPrefix: String = "extra"

  /** `prefix` must be followed by `@` or nothing: `actions/cache/restore@v4` is a different action from
    * `actions/cache`. The bare form matches so an unpinned `uses: actions/checkout` is refused, not skipped.
    */
  def namesPrefix(refOrName: String, prefix: String): Boolean =
    refOrName == prefix || refOrName.startsWith(prefix + "@")

  /** Rows naming a field prefix update that field; any other row becomes an extra pin keyed by name. */
  def overlay(base: ActionPins, rows: Seq[Action]): Either[String, ActionPins] =
    val dupNames = rows.groupBy(_.name).collect { case (n, xs) if xs.size > 1 => n }.toList.sorted
    if dupNames.nonEmpty then
      Left(
        s"duplicate Action name '${dupNames.head}' in ZipxVersions. Keep one val per owner/repo[/path]."
      )
    else
      rows.foldLeft[Either[String, ActionPins]](Right(base)) { (accE, action) =>
        accE.flatMap(pins => overlayOne(pins, action))
      }
  end overlay

  private def overlayOne(pins: ActionPins, action: Action): Either[String, ActionPins] =
    action.toRef.flatMap { ref =>
      Field.values.find(_.prefix == action.name) match
        case Some(field) =>
          if !namesPrefix(ref.unwrap, field.prefix) then
            Left(s"Action '${action.name}' sha does not name ${field.prefix}")
          else
            Right(
              pins.withField(field, ref).copy(versions = pins.versions.updated(field.key, action.version: String))
            )
        case None =>
          Right(pins.withExtra(action.name, ref, Some(action.version: String)))
    }

  /** Declaration order is the line order of the rendered `zipx/action-pins.yml`. */
  enum Field(val key: String, val prefix: String):
    case Checkout         extends Field("checkout", "actions/checkout")
    case SetupJava        extends Field("setupJava", "actions/setup-java")
    case SetupSbt         extends Field("setupSbt", "sbt/setup-sbt")
    case SetupNode        extends Field("setupNode", "actions/setup-node")
    case Cache            extends Field("cache", "actions/cache")
    case UploadArtifact   extends Field("uploadArtifact", "actions/upload-artifact")
    case DownloadArtifact extends Field("downloadArtifact", "actions/download-artifact")

  // Fallbacks for when the classpath pin resource is missing.
  private[core] val BootstrapCheckout: ActionRef =
    ActionRef("actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1")
  private[core] val BootstrapSetupJava: ActionRef =
    ActionRef("actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961")
  private[core] val BootstrapSetupSbt: ActionRef =
    ActionRef("sbt/setup-sbt@5ed7fa239084076151f66fa1dc45d4363b2dfee5")
  private[core] val BootstrapSetupNode: ActionRef =
    ActionRef("actions/setup-node@820762786026740c76f36085b0efc47a31fe5020")
  private[core] val BootstrapCache: ActionRef =
    ActionRef("actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9")
  private[core] val BootstrapUploadArtifact: ActionRef =
    ActionRef("actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a")
  private[core] val BootstrapDownloadArtifact: ActionRef =
    ActionRef("actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c")

  private[core] val BootstrapVersions: Map[String, String] = Map(
    Field.Checkout.key         -> "v7.0.1",
    Field.SetupJava.key        -> "v5.7.0",
    Field.SetupSbt.key         -> "v1.5.7",
    Field.SetupNode.key        -> "v7.0.0",
    Field.Cache.key            -> "v6.1.0",
    Field.UploadArtifact.key   -> "v7.0.1",
    Field.DownloadArtifact.key -> "v8.0.1",
  )

  private[core] val Bootstrap: ActionPins = ActionPins(
    BootstrapCheckout,
    BootstrapSetupJava,
    BootstrapSetupSbt,
    BootstrapSetupNode,
    BootstrapCache,
    BootstrapUploadArtifact,
    BootstrapDownloadArtifact,
    BootstrapVersions,
  )

  lazy val Defaults: ActionPins =
    ActionPinFile.loadResource().getOrElse(Bootstrap)

  def Checkout: ActionRef         = Defaults.checkout
  def SetupJava: ActionRef        = Defaults.setupJava
  def SetupSbt: ActionRef         = Defaults.setupSbt
  def SetupNode: ActionRef        = Defaults.setupNode
  def Cache: ActionRef            = Defaults.cache
  def UploadArtifact: ActionRef   = Defaults.uploadArtifact
  def DownloadArtifact: ActionRef = Defaults.downloadArtifact

end ActionPins
