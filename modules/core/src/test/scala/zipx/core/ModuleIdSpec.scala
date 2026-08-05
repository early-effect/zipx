package zipx.core

import zio.test.*
import zipx.workflow.ExprLiteral
import zipx.workflow.JobId
import zipx.workflow.Names

object ModuleIdSpec extends ZIOSpecDefault:

  /** The characters an sbt project id can contain, plus the ones GitHub rejects. Not the whole of Unicode: enough of it
    * to cover each class the two rules disagree about.
    */
  private val Alphabet: List[Char] =
    ('a' to 'z').toList ++ ('A' to 'Z').toList ++ ('0' to '9').toList ++
      List('_', '-', '.', '/', '@', '+', ':', ' ', '\'', '$', '{', '}', 'é', 'Ü', 'プ')

  def spec = suite("ModuleId")(
    test("accepts the ids an sbt build normally has") {
      assertTrue(
        ModuleId.make("core").isRight,
        ModuleId.make("sbt-plugin").isRight,
        ModuleId.make("core_2_13").isRight,
        ModuleId.make("_build").isRight,
        ModuleId.make("A1").isRight,
      )
    },
    test("rejects an sbt-legal Unicode id, which is the bug this newtype exists for") {
      // sbt's own rule is `Character.isLetter` then `isLetterOrDigit || '-' || '_'`, so all four of these load fine and
      // would previously have reached the planner and thrown mid-plan.
      assertTrue(
        ModuleId.make("café").isLeft,
        ModuleId.make("プロジェクト").isLeft,
        ModuleId.make("naïve-lib").isLeft,
        ModuleId.make("Ünicode").isLeft,
      )
    },
    test("rejects an empty id and one starting with a digit, and says which rule it broke") {
      assertTrue(
        ModuleId.make("").swap.exists(_.contains("non-empty")),
        ModuleId.make("1core").swap.exists(_.contains("must start with an ASCII letter")),
        ModuleId.make("a b").isLeft,
        ModuleId.make("a.b").isLeft,
      )
    },
    test("asExprLiteral is total: every legal module id is a legal expression literal") {
      // The claim `ModuleId.asExprLiteral` relies on, checked over the alphabet in both positions rather than trusted:
      // a first character followed by each possible second character.
      val ids = for first <- Alphabet; second <- Alphabet yield s"$first$second"
      assertTrue(
        ids.filter(id => ModuleId.make(id).isRight).forall(id => ExprLiteral.make(id).isRight),
        ids.exists(id => ModuleId.make(id).isLeft && ExprLiteral.make(id).isRight),
      )
    },
    test("the subset is strict, so the two rules are not interchangeable") {
      // Each of these is a legal expression literal and an illegal module id. That asymmetry is why the conversion goes
      // one way only, and why `ExprLiteral` cannot stand in for `ModuleId` at the graph boundary.
      assertTrue(
        List("1abc", "a.b", "a/b", "a@b", "a+b", "a:b")
          .forall(text => ExprLiteral.make(text).isRight && ModuleId.make(text).isLeft)
      )
    },
    test("fromJobId carries a synthetic job's identity onto its node") {
      assertTrue(ModuleId.fromJobId(JobId("cache-rehydrate")) == "cache-rehydrate")
    },
    test("a ModuleId is a String, so reading one in a build.sbt needs no unwrapping") {
      val id = ModuleId("api")
      assertTrue(
        id == "api",
        s"$id/test" == "api/test",
        Map[String, Int]("api" -> 1).get(id).contains(1),
      )
    },
    test("the validator is the same rule the graph's job ids are held to") {
      // `ModuleId` duplicates `Names.ActionsId` rather than deriving from `JobId`, so this pins the two together: if the
      // job-id rule moves, this fails rather than the workflow silently becoming invalid.
      assertTrue(
        ModuleId.make("ok-1").isRight == "ok-1".matches(Names.ActionsId),
        ModuleId.make("no.1").isRight == "no.1".matches(Names.ActionsId),
      )
    },
    test("a bad literal id is rejected while the build compiles, not when it runs") {
      for
        unicode <- typeCheck("""zipx.core.ModuleId("café")""")
        digit   <- typeCheck("""zipx.core.ModuleId("1core")""")
        good    <- typeCheck("""zipx.core.ModuleId("core")""")
      yield assertTrue(unicode.isLeft, digit.isLeft, good.isRight)
    },
  )
end ModuleIdSpec
