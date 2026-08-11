package zipx.core

/** How a capability obtains the sbt command a job types at the shell.
  *
  * Exactly three shapes. Construct via [[Capability.running]], [[Capability.runningEach]],
  * [[Capability.runningEachCross]], [[Capability.runningPerModule]], or [[Capability.runningNothing]] (and the built-in
  * / `once` / `steps` factories). Do not invent a fourth encoding with `Option` inside a lambda.
  *
  *   - [[ActionsOnly]]: no sbt session
  *   - [[Fixed]]: one command for the job
  *   - [[PerModule]]: one command per participating module
  */
enum CommandSource:

  /** No sbt. JDK / sbt / cache toolchain omitted; checkout + [[Capability.extraSteps]] / [[Capability.postSteps]] only.
    * From [[Capability.steps]] / [[Capability.runningNothing]].
    */
  case ActionsOnly

  /** One command for the job. From [[Capability.once]] / [[Capability.running]]. */
  case Fixed(command: SbtCommand)

  /** Per participating [[ModuleNode]]. From [[Capability.runningEach]], [[Capability.runningEachCross]],
    * [[Capability.runningPerModule]], and built-in per-module factories.
    */
  case PerModule(build: ModuleNode => SbtCommand)

  /** True for [[Fixed]] and [[PerModule]]; false for [[ActionsOnly]]. */
  def runsSbt: Boolean = this match
    case CommandSource.ActionsOnly                           => false
    case CommandSource.Fixed(_) | CommandSource.PerModule(_) => true

  /** The command for `node`. Valid only when [[runsSbt]] is true; [[ActionsOnly]] is a programmer error here.
    *
    * Callers that must handle actions-only jobs match on this enum (or branch on [[runsSbt]]) instead of probing
    * `Option`.
    */
  def commandFor(node: ModuleNode): SbtCommand = this match
    case CommandSource.ActionsOnly =>
      sys.error("CommandSource.ActionsOnly has no sbt command; branch on runsSbt or match the enum first")
    case CommandSource.Fixed(c)      => c
    case CommandSource.PerModule(fn) => fn(node)

  def declaredNames: List[SbtCommandName] = this match
    case CommandSource.ActionsOnly   => Nil
    case CommandSource.Fixed(c)      => c.declaredNames
    case CommandSource.PerModule(fn) => fn(ModuleNode.probe).declaredNames

  def rawFragments: List[String] = this match
    case CommandSource.ActionsOnly   => Nil
    case CommandSource.Fixed(c)      => c.rawFragments
    case CommandSource.PerModule(fn) => fn(ModuleNode.probe).rawFragments
end CommandSource
