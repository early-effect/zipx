package zipx.docs

import specular.*
import zio.test.*

/** JVM half of the mount contract for ascent interactives (see specular's InteractiveContractSpec). */
object InteractiveContractSpec extends ZIOSpecDefault:

  def spec = suite("Interactive contract")(
    test("every .interactive example declares a mount key equal to its id") {
      val interactive = collectInteractive(BuildSite.pages)
      assertTrue(
        interactive.nonEmpty,
        interactive.forall((id, key) => key.contains(id)),
      )
    },
    test("the nav's pages declare the same mount keys as the client's page list") {
      val fromNav    = DocMounts.keys(BuildSite.pages*)
      val fromClient = DocMounts.keys(clientPages*)
      assertTrue(fromNav.filter(_.contains("matrix-collapse")).nonEmpty || fromClient.nonEmpty)
      assertTrue(fromClient.forall(fromNav.contains))
    },
    test("mount keys are unique across the whole site") {
      val all = DocMounts.keyList(BuildSite.pages*)
      assertTrue(all.distinct.size == all.size)
    },
  )

  /** Same list [[ClientMain]] registers; duplicated because this JVM spec cannot see that object. */
  private val clientPages: Vector[DocPage] = Vector(MatrixCollapsePage.doc, ComposingSbtCommands.doc)

  private def collectInteractive(pages: Vector[DocPage]): Vector[(String, Option[String])] =
    def go(nodes: Vector[DocNode]): Vector[(String, Option[String])] =
      nodes.flatMap {
        case ex: Example[?] if ex.isInteractive => Vector(ex.id -> ex.mountKey)
        case Section(_, kids)                   => go(kids)
        case _                                  => Vector.empty
      }
    pages.flatMap(p => go(p.children))
end InteractiveContractSpec
