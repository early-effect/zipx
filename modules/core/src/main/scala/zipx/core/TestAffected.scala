package zipx.core

import zipx.workflow.Expr

/** What `zipxTestAffected` runs in place of the full `test` command: only CI-relevant modules the root aggregate
  * reaches, since a root `testFull` never runs others and an aggregator's own test task would rerun its modules.
  */
object TestAffected:

  val CommandName: String = "zipxTestAffected"

  /** GitHub substitutes `base` before the shell runs. An empty base tests everything. */
  def command(base: Expr): SbtCommand =
    SbtCommand.fromSteps(List(SbtStep.Built(SbtCommandText.unsafeMake(s"$CommandName ${base.render}"))))

  enum Run:
    /** The diff could not narrow anything. */
    case Everything(command: SbtCommand)

    case Modules(command: SbtCommand, modules: ::[ModuleId])

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
        case first :: rest => Run.Modules(together(nodes), ::(first.id, rest.map(_.id)))

  /** `all a/testFull b/testFull` runs one parallel task graph, where `;` would run modules one after another. `all`
    * takes tasks only, so a session or command test task falls back to a sequential session in dependency order.
    */
  private def together(nodes: List[ModuleNode]): SbtCommand =
    val commands = nodes.map(n => SbtCommand.module(n, n.testTask))
    if nodes.forall(n => isSingleTask(n.testTask)) then
      SbtCommand.fromSteps(
        List(SbtStep.Built(SbtCommandText.unsafeMake(("all" :: commands.map(c => c.text: String)).mkString(" "))))
      )
    else SbtCommand.session(commands.head, commands.tail*)

  private def isSingleTask(command: SbtCommand): Boolean = command.steps match
    case List(SbtStep.Task(_, TaskScope.Unscoped, false)) => true
    case _                                                => false

end TestAffected
