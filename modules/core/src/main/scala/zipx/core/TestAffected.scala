package zipx.core

import zipx.workflow.Expr

/** What `zipxTestAffected` runs in place of the builtin `test` job's full command.
  *
  * Only CI-relevant modules the root aggregate reaches: a root `testFull` never runs a module outside it, and an
  * aggregator's own test task would run every module it aggregates a second time.
  */
object TestAffected:

  /** The sbt command the plugin defines. */
  val CommandName: String = "zipxTestAffected"

  /** `zipxTestAffected <base>`, where `base` is an expression GitHub substitutes before the shell runs. An empty base
    * tests everything.
    */
  def command(base: Expr): SbtCommand =
    SbtCommand.fromSteps(List(SbtStep.Built(SbtCommandText.unsafeMake(s"$CommandName ${base.render}"))))

  enum Run:
    /** The diff could not narrow anything: today's full command. */
    case Everything(command: SbtCommand)

    /** Each affected module's own test task, in dependency order. */
    case Modules(command: SbtCommand, modules: ::[ModuleId])

    /** Nothing the root aggregate reaches is affected. */
    case Nothing

  /** @param affected
    *   [[Affected.outputModules]]' answer, where [[Affected.AllSentinel]] means everything.
    */
  def plan(full: SbtCommand, graph: ModuleGraph, aggregated: Set[ModuleId], affected: List[String]): Run =
    if affected == Affected.AllSentinel then Run.Everything(full)
    else
      val nodes =
        graph.topologicalSort
          .flatMap(graph.get)
          .filter(n => n.ciRelevant && aggregated.contains(n.id) && affected.contains(n.id))
      nodes match
        case Nil           => Run.Nothing
        case first :: rest =>
          val commands = nodes.map(n => SbtCommand.module(n, n.testTask))
          Run.Modules(SbtCommand.session(commands.head, commands.tail*), ::(first.id, rest.map(_.id)))

end TestAffected
