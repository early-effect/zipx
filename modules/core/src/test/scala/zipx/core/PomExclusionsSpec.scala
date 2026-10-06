package zipx.core

import zio.test.*

import RevisionGens.{commitOn, line as lineGen, parsed}

object PomExclusionsSpec extends ZIOSpecDefault:

  private def module(index: Int): ResolvedModule = ResolvedModule("com.example", s"m${index}_3")

  private val root = module(0)

  private val scope: Gen[Any, PomScope] = Gen.elements(PomScope.values.toList*)

  /** Acyclic by construction: a project only depends on a later one. */
  private val inRepo: Gen[Any, Map[ResolvedModule, List[PomEdge]]] =
    for
      count <- Gen.int(2, 7)
      pairs = for from <- 0 until count; to <- (from + 1) until count yield (from, to)
      edges <- Gen.listOfN(pairs.size)(Gen.option(scope))
    yield pairs
      .zip(edges)
      .collect { case ((from, to), Some(edge)) => module(from) -> PomEdge(edge, module(to)) }
      .toList
      .groupMap((from, _) => from)((_, edge) => edge)

  private def inheritedFrom(graph: Map[ResolvedModule, List[PomEdge]], from: ResolvedModule): List[ResolvedModule] =
    graph.getOrElse(from, Nil).collect { case PomEdge(scope, to) if scope.inherited => to }

  def spec = suite("PomExclusions")(
    test("every in-repo module a consumer inherits through the project is excluded") {
      check(inRepo) { graph =>
        val excluded = PomExclusions.of(root, graph, Nil).toSet
        assertTrue((excluded + root).forall(from => inheritedFrom(graph, from).forall(excluded.contains)))
      }
    },
    test("an excluded in-repo module is one a consumer inherits, and the project itself never is") {
      check(inRepo) { graph =>
        val excluded = PomExclusions.of(root, graph, Nil).toSet
        assertTrue(
          !excluded.contains(root),
          excluded.forall(to => (excluded + root).exists(from => inheritedFrom(graph, from).contains(to))),
        )
      }
    },
    test("a commit pin is excluded when a consumer inherits it, and a release never is") {
      val gen = for
        line   <- lineGen
        pin    <- commitOn(line)
        scoped <- scope
      yield (DepRevision.Commit(parsed(pin)), DepRevision.Release(line), scoped)
      check(gen) { (commit, release, scoped) =>
        val library = ResolvedModule("com.example", "heddle_3")
        assertTrue(
          PomExclusions.of(root, Map.empty, List(PomDependency(scoped, library, commit))) ==
            (if scoped.inherited then List(library) else Nil),
          PomExclusions.of(root, Map.empty, List(PomDependency(scoped, library, release))).isEmpty,
        )
      }
    },
    test("a resolved module that names more than one project is refused") {
      val edge = PomEdge(PomScope.Compile, module(1))
      assertTrue(
        PomExclusions.graph(List(root -> Nil, module(1) -> List(edge))) ==
          Right(Map(root -> Nil, module(1) -> List(edge))),
        PomExclusions.graph(List(root -> List(edge), root -> Nil)) == Left(DuplicateModule(root)),
      )
    },
    test("compile and runtime chains count; test, provided, optional, and tool edges do not") {
      val (a, b, t, p, o) = (module(1), module(2), module(3), module(4), module(5))
      val graph           = Map(
        root -> List(
          PomEdge(PomScope.Compile, b),
          PomEdge(PomScope.Test, t),
          PomEdge(PomScope.Provided, p),
          PomEdge(PomScope.Optional, o),
        ),
        b -> List(PomEdge(PomScope.Runtime, a)),
      )
      assertTrue(PomExclusions.of(root, graph, Nil) == List(a, b))
    },
  )
end PomExclusionsSpec
