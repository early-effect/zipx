package zipx.core

/** Hash-pinned GitHub Actions used in generated workflows.
  *
  * Editable source of truth in a repo is [[ActionPinFile.DefaultPath]] (`.github/zipx/action-pins.yml`). Published
  * [[ActionPins.Defaults]] are that file embedded on the classpath at build time. Override via the pin file
  * (Dependabot-friendly) or, for one-offs, `zipxActions` in `build.sbt`:
  *
  * {{{
  * zipxActions := ActionPins.Defaults.copy(
  *   checkout = "actions/checkout@<sha>",
  * )
  * }}}
  *
  * @param checkout
  *   `actions/checkout` pin (`owner/action@sha`).
  * @param setupJava
  *   `actions/setup-java` pin.
  * @param setupSbt
  *   `sbt/setup-sbt` pin.
  * @param cache
  *   `actions/cache` pin for [[CacheBackend.LocalDir]].
  * @param uploadArtifact
  *   `actions/upload-artifact` pin (Central staging share).
  * @param downloadArtifact
  *   `actions/download-artifact` pin (Central staging reassembly).
  * @param scalaSteward
  *   `scala-steward-org/scala-steward-action` pin (opt-in [[ScalaStewardWorkflow]]).
  * @param versions
  *   Optional semver labels (`v7.0.1`) keyed by field name for `# vX.Y.Z` comments on generated `uses:` lines.
  */
final case class ActionPins(
    checkout: String = ActionPins.BootstrapCheckout,
    setupJava: String = ActionPins.BootstrapSetupJava,
    setupSbt: String = ActionPins.BootstrapSetupSbt,
    cache: String = ActionPins.BootstrapCache,
    uploadArtifact: String = ActionPins.BootstrapUploadArtifact,
    downloadArtifact: String = ActionPins.BootstrapDownloadArtifact,
    scalaSteward: String = ActionPins.BootstrapScalaSteward,
    versions: Map[String, String] = Map.empty,
):
  import ActionPins.Field

  def field(f: Field): String = f match
    case Field.Checkout         => checkout
    case Field.SetupJava        => setupJava
    case Field.SetupSbt         => setupSbt
    case Field.Cache            => cache
    case Field.UploadArtifact   => uploadArtifact
    case Field.DownloadArtifact => downloadArtifact
    case Field.ScalaSteward     => scalaSteward

  def withField(f: Field, ref: String): ActionPins = f match
    case Field.Checkout         => copy(checkout = ref)
    case Field.SetupJava        => copy(setupJava = ref)
    case Field.SetupSbt         => copy(setupSbt = ref)
    case Field.Cache            => copy(cache = ref)
    case Field.UploadArtifact   => copy(uploadArtifact = ref)
    case Field.DownloadArtifact => copy(downloadArtifact = ref)
    case Field.ScalaSteward     => copy(scalaSteward = ref)

  def version(f: Field): Option[String] = versions.get(f.key)

end ActionPins

object ActionPins:

  /** The pins, enumerated: one case per field of [[ActionPins]].
    *
    * Declaration order is the line order of the committed `.github/zipx/action-pins.yml`, since
    * [[ActionPinFile.render]] folds over `Field.values`.
    */
  enum Field(val key: String, val prefix: String):
    case Checkout         extends Field("checkout", "actions/checkout")
    case SetupJava        extends Field("setupJava", "actions/setup-java")
    case SetupSbt         extends Field("setupSbt", "sbt/setup-sbt")
    case Cache            extends Field("cache", "actions/cache")
    case UploadArtifact   extends Field("uploadArtifact", "actions/upload-artifact")
    case DownloadArtifact extends Field("downloadArtifact", "actions/download-artifact")
    case ScalaSteward     extends Field("scalaSteward", "scala-steward-org/scala-steward-action")

  // Bootstrap fallbacks (keep in sync with `.github/zipx/action-pins.yml`). Used only when the classpath resource is
  // missing, e.g. incomplete dogfood classpath. Prefer [[ActionPins.Defaults]] from the embedded pin file.
  private[core] val BootstrapCheckout: String =
    "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
  private[core] val BootstrapSetupJava: String =
    "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961"
  private[core] val BootstrapSetupSbt: String =
    "sbt/setup-sbt@bfea3c5f48abd221b04a6df4798aa5eb8b6a2baf"
  private[core] val BootstrapCache: String =
    "actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9"
  private[core] val BootstrapUploadArtifact: String =
    "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"
  private[core] val BootstrapDownloadArtifact: String =
    "actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c"
  private[core] val BootstrapScalaSteward: String =
    "scala-steward-org/scala-steward-action@41bd88543dcf5e5455689f04d041b095eb901660"

  private[core] val BootstrapVersions: Map[String, String] = Map(
    Field.Checkout.key         -> "v7.0.1",
    Field.SetupJava.key        -> "v5.7.0",
    Field.SetupSbt.key         -> "v1.5.6",
    Field.Cache.key            -> "v6.1.0",
    Field.UploadArtifact.key   -> "v7.0.1",
    Field.DownloadArtifact.key -> "v8.0.1",
    Field.ScalaSteward.key     -> "v2.93.0",
  )

  private[core] val Bootstrap: ActionPins = ActionPins(
    BootstrapCheckout,
    BootstrapSetupJava,
    BootstrapSetupSbt,
    BootstrapCache,
    BootstrapUploadArtifact,
    BootstrapDownloadArtifact,
    BootstrapScalaSteward,
    BootstrapVersions,
  )

  /** Current zipx defaults, loaded from classpath `zipx/action-pins.yml` when present. */
  lazy val Defaults: ActionPins =
    ActionPinFile.loadResource().getOrElse(Bootstrap)

  /** Convenience aliases matching older call sites / docs. */
  def Checkout: String         = Defaults.checkout
  def SetupJava: String        = Defaults.setupJava
  def SetupSbt: String         = Defaults.setupSbt
  def Cache: String            = Defaults.cache
  def UploadArtifact: String   = Defaults.uploadArtifact
  def DownloadArtifact: String = Defaults.downloadArtifact
  def ScalaSteward: String     = Defaults.scalaSteward

end ActionPins
