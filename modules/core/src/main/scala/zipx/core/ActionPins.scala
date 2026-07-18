package zipx.core

/** Hash-pinned GitHub Actions used in generated workflows.
  *
  * Defaults are full commit SHAs (not mutable tags like `@v4`) so generated CI is reproducible and resistant to tag
  * moves. Override via `zipxActions` in `build.sbt`:
  *
  * {{{
  * zipxActions := ActionPins.Defaults.copy(
  *   checkout = "actions/checkout@<sha>", // bump when you choose
  * )
  * }}}
  *
  * Version labels in the scaladoc / [[ActionPins]] object comments are informational; the `uses:` value is the SHA pin.
  *
  * @param checkout
  *   `actions/checkout` pin (default v7.0.0).
  * @param setupJava
  *   `actions/setup-java` pin (default v5.6.0).
  * @param setupSbt
  *   `sbt/setup-sbt` pin (default v1.5.1).
  * @param cache
  *   `actions/cache` pin for [[CacheBackend.LocalDir]] (default v6.1.0).
  */
final case class ActionPins(
  checkout: String = ActionPins.Checkout,
  setupJava: String = ActionPins.SetupJava,
  setupSbt: String = ActionPins.SetupSbt,
  cache: String = ActionPins.Cache,
)

object ActionPins:

  /** actions/checkout@v7.0.0 */
  val Checkout: String = "actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0"

  /** actions/setup-java@v5.6.0 */
  val SetupJava: String = "actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95"

  /** sbt/setup-sbt@v1.5.1 */
  val SetupSbt: String = "sbt/setup-sbt@9d56cf12e9b58d219605e1d8bfe69a8395fedde0"

  /** actions/cache@v6.1.0 */
  val Cache: String = "actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9"

  /** Current zipx defaults — prefer this over assembling pins by hand. */
  val Defaults: ActionPins = ActionPins()

end ActionPins
