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
  * Fields are [[zipx.workflow.ActionRef]], so a literal is checked while the build compiles. A pin therefore reaches
  * `Step.uses` with nothing left to validate, which is what makes an unpinned `uses:` unrepresentable rather than
  * merely rejected.
  *
  * @param checkout
  *   `actions/checkout` pin (`owner/action@sha`).
  * @param setupJava
  *   `actions/setup-java` pin.
  * @param setupSbt
  *   `sbt/setup-sbt` pin.
  * @param setupNode
  *   `actions/setup-node` pin, emitted only for a capability that asks for a Node version
  *   ([[Capability.withNodeVersion]]).
  * @param cache
  *   `actions/cache` pin for [[CacheBackend.LocalDir]].
  * @param uploadArtifact
  *   `actions/upload-artifact` pin (Central staging share).
  * @param downloadArtifact
  *   `actions/download-artifact` pin (Central staging reassembly).
  * @param versions
  *   Optional semver labels (`v7.0.1`) keyed by field name for `# vX.Y.Z` comments on generated `uses:` lines. An extra
  *   pin's label is keyed `extra.<key>`, which no [[ActionPins.Field.key]] can collide with because a field key has no
  *   dot in it.
  * @param extra
  *   Pins for actions zipx does not emit itself, keyed by the action name (`owner/repo`). Packs look up by prefix
  *   ([[extraByPrefix]]), not a YAML `extra:` key.
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

  /** Pins an action zipx does not emit, for a step a consumer or a pack writes.
    *
    * `version` is the `# vX.Y.Z` label. Key is the action name (`owner/repo`) so [[extraByPrefix]] can find it.
    */
  def withExtra(key: String, ref: ActionRef, version: Option[String] = None): ActionPins =
    copy(
      extra = extra.updated(key, ref),
      versions = version.fold(versions - ActionPins.extraVersionKey(key))(v =>
        versions.updated(ActionPins.extraVersionKey(key), v)
      ),
    )

  def extraRef(key: String): Option[ActionRef] = extra.get(key)

  def extraVersion(key: String): Option[String] = versions.get(ActionPins.extraVersionKey(key))

  /** The extra pin whose key or ref names `prefix` (`owner/repo`). */
  def extraByPrefix(prefix: String): Option[ActionRef] =
    extra.collectFirst {
      case (key, ref) if key == prefix || ActionPins.namesPrefix(ref.unwrap, prefix) => ref
    }

end ActionPins

object ActionPins:

  /** The `versions` key an extra pin's label lives under. The `extra.` prefix is what keeps one namespace safe for
    * both: a [[Field.key]] is a bare identifier, so it can never contain a dot.
    */
  private[core] def extraVersionKey(key: String): String = s"$ExtraPrefix.$key"

  /** The pin-file block name for [[ActionPins.extra]], and the prefix of its `versions` keys. */
  private[core] val ExtraPrefix: String = "extra"

  def namesPrefix(refOrName: String, prefix: String): Boolean =
    refOrName == prefix || refOrName.startsWith(prefix + "@")

  /** Overlay catalog [[Action]] rows onto jar defaults. Field prefixes update typed fields; anything else is extra
    * keyed by name. Duplicate name or duplicate field prefix is Left.
    */
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

  /** The pins, enumerated: one case per field of [[ActionPins]].
    *
    * Declaration order is the line order of rendered `zipx/action-pins.yml`, since [[ActionPinFile.render]] folds over
    * `Field.values`.
    */
  enum Field(val key: String, val prefix: String):
    case Checkout         extends Field("checkout", "actions/checkout")
    case SetupJava        extends Field("setupJava", "actions/setup-java")
    case SetupSbt         extends Field("setupSbt", "sbt/setup-sbt")
    case SetupNode        extends Field("setupNode", "actions/setup-node")
    case Cache            extends Field("cache", "actions/cache")
    case UploadArtifact   extends Field("uploadArtifact", "actions/upload-artifact")
    case DownloadArtifact extends Field("downloadArtifact", "actions/download-artifact")

  // Bootstrap fallbacks. Used only when the classpath resource is missing. Prefer [[ActionPins.Defaults]] from the
  // embedded pin file. Literals, so each one's shape is checked while this file compiles.
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

  /** Current zipx defaults, loaded from classpath `zipx/action-pins.yml` when present. */
  lazy val Defaults: ActionPins =
    ActionPinFile.loadResource().getOrElse(Bootstrap)

  /** Convenience aliases matching older call sites / docs. */
  def Checkout: ActionRef         = Defaults.checkout
  def SetupJava: ActionRef        = Defaults.setupJava
  def SetupSbt: ActionRef         = Defaults.setupSbt
  def SetupNode: ActionRef        = Defaults.setupNode
  def Cache: ActionRef            = Defaults.cache
  def UploadArtifact: ActionRef   = Defaults.uploadArtifact
  def DownloadArtifact: ActionRef = Defaults.downloadArtifact

end ActionPins
