package zipx.core

/** Hash-pinned GitHub Actions used in generated workflows.
  *
  * Editable source of truth in a repo is [[ActionPinFile.DefaultPath]] (`.github/zipx/action-pins.yml`). Published
  * [[Defaults]] are that file embedded on the classpath at build time. Override via the pin file (Dependabot-friendly)
  * or, for one-offs, `zipxActions` in `build.sbt`:
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
    versions: Map[String, String] = Map.empty,
):
  def field(name: String): String = name match
    case "checkout"         => checkout
    case "setupJava"        => setupJava
    case "setupSbt"         => setupSbt
    case "cache"            => cache
    case "uploadArtifact"   => uploadArtifact
    case "downloadArtifact" => downloadArtifact
    case other              => throw new IllegalArgumentException(s"Unknown action pin field: $other")
end ActionPins

object ActionPins:

  // Bootstrap fallbacks (keep in sync with `.github/zipx/action-pins.yml`). Used only when the classpath resource is
  // missing — e.g. incomplete dogfood classpath. Prefer [[Defaults]] from the embedded pin file.
  private[core] val BootstrapCheckout: String =
    "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
  private[core] val BootstrapSetupJava: String =
    "actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95"
  private[core] val BootstrapSetupSbt: String =
    "sbt/setup-sbt@6444f4c8111de4b9059c3975def104b03cfaa5f0"
  private[core] val BootstrapCache: String =
    "actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9"
  private[core] val BootstrapUploadArtifact: String =
    "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"
  private[core] val BootstrapDownloadArtifact: String =
    "actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c"

  private[core] val BootstrapVersions: Map[String, String] = Map(
    "checkout"         -> "v7.0.1",
    "setupJava"        -> "v5.6.0",
    "setupSbt"         -> "v1.5.2",
    "cache"            -> "v6.1.0",
    "uploadArtifact"   -> "v7.0.1",
    "downloadArtifact" -> "v8.0.1",
  )

  /** Current zipx defaults — loaded from classpath `zipx/action-pins.yml` when present. */
  lazy val Defaults: ActionPins =
    try ActionPinFile.loadResource()
    catch
      case _: IllegalStateException =>
        ActionPins(
          BootstrapCheckout,
          BootstrapSetupJava,
          BootstrapSetupSbt,
          BootstrapCache,
          BootstrapUploadArtifact,
          BootstrapDownloadArtifact,
          BootstrapVersions,
        )

  /** Convenience aliases matching older call sites / docs. */
  def Checkout: String         = Defaults.checkout
  def SetupJava: String        = Defaults.setupJava
  def SetupSbt: String         = Defaults.setupSbt
  def Cache: String            = Defaults.cache
  def UploadArtifact: String   = Defaults.uploadArtifact
  def DownloadArtifact: String = Defaults.downloadArtifact

end ActionPins
