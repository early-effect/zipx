package zipx.core

/** How a capability obtains the sbt command a job runs, built by the [[Capability]] `running*` methods. */
enum CommandSource:

  /** No JDK / sbt / cache toolchain: checkout plus [[Capability.extraSteps]] / [[Capability.postSteps]] only. */
  case ActionsOnly

  /** One command for the whole job. */
  case Fixed(command: SbtCommand)

  case PerModule(build: ModuleNode => SbtCommand)

  def runsSbt: Boolean = this match
    case CommandSource.ActionsOnly                           => false
    case CommandSource.Fixed(_) | CommandSource.PerModule(_) => true

  /** Fails on [[CommandSource.ActionsOnly]]: match the enum or branch on [[runsSbt]] first. */
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
