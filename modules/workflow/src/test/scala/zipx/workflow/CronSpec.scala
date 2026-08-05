package zipx.workflow

import zio.test.*

object CronSpec extends ZIOSpecDefault:

  def spec = suite("Cron")(
    test("weekly Sunday midnight renders Steward's default cron") {
      assertTrue(Cron.weekly(DayOfWeek.Sunday).render == "0 0 * * 0")
    },
    test("weekly Monday 6:30 UTC") {
      assertTrue(Cron.weekly(DayOfWeek.Monday, hour = 6, minute = 30).render == "30 6 * * 1")
    },
    test("DayOfWeek cron values are Sunday=0 through Saturday=6") {
      val expected = List(0, 1, 2, 3, 4, 5, 6)
      val actual   = DayOfWeek.values.map(_.cronValue).toList
      assertTrue(actual == expected)
    },

    test("daily and hourly helpers") {
      assertTrue(
        Cron.daily(hour = 3, minute = 15).render == "15 3 * * *",
        Cron.hourly(minute = 45).render == "45 * * * *",
      )
    },

    test("accepts max valid hour and minute") {
      assertTrue(
        Cron.daily(hour = 23, minute = 59).render == "59 23 * * *",
        Cron.hourly(minute = 59).render == "59 * * * *",
      )
    },

    test("an out-of-range literal hour does not compile") {
      for
        ok    <- typeCheck("""Cron.daily(hour = 23)""")
        under <- typeCheck("""Cron.daily(hour = -1)""")
        over  <- typeCheck("""Cron.daily(hour = 24)""")
      yield assertTrue(ok.isRight, under.isLeft, over.isLeft, over.left.exists(_.contains("0 to 23")))
    },
    test("an out-of-range literal minute does not compile") {
      for
        ok    <- typeCheck("""Cron.hourly(minute = 59)""")
        under <- typeCheck("""Cron.hourly(minute = -1)""")
        over  <- typeCheck("""Cron.hourly(minute = 60)""")
      yield assertTrue(ok.isRight, under.isLeft, over.isLeft, over.left.exists(_.contains("0 to 59")))
    },
    test("an out-of-range runtime hour or minute is a Left, not an exception") {
      assertTrue(
        Cron.dailyMake(24, 0).isLeft,
        Cron.dailyMake(-1, 0).isLeft,
        Cron.dailyMake(0, 60).isLeft,
        Cron.hourlyMake(60).isLeft,
        Cron.weeklyMake(DayOfWeek.Monday, 24, 0).isLeft,
        Cron.dailyMake(24, 0).left.exists(_.contains("0 to 23")),
        Cron.hourlyMake(60).left.exists(_.contains("0 to 59")),
      )
    },

    test("raw accepts a valid five-field expression") {
      assertTrue(Cron.raw("0 */6 * * *").render == "0 */6 * * *")
    },
    test("raw render is stable for canonical expressions") {
      val exprs = List(
        "0 0 * * 0",
        "*/15 * * * *",
        "0 0 1 * *",
        "30 4 * * 1-5",
      )
      assertTrue(exprs.flatMap(Cron.rawMake(_).toOption).map(_.render) == exprs)
    },

    test("an invalid literal raw expression does not compile") {
      for
        ok    <- typeCheck("""Cron.raw("0 0 * * 0")""")
        empty <- typeCheck("""Cron.raw("")""")
        ws    <- typeCheck("""Cron.raw("   ")""")
        four  <- typeCheck("""Cron.raw("0 0 * *")""")
        six   <- typeCheck("""Cron.raw("0 0 * * * *")""")
      yield assertTrue(ok.isRight, empty.isLeft, ws.isLeft, four.isLeft, six.isLeft)
    },
    test("an invalid runtime raw expression is a Left") {
      assertTrue(
        Cron.rawMake("").isLeft,
        Cron.rawMake("   ").isLeft,
        Cron.rawMake("0 0 * *").isLeft,
        Cron.rawMake("0 0 * * * *").isLeft,
        Cron.rawMake("0 0 * *").left.exists(_.contains("five")),
      )
    },

    test("daily succeeds and round-trips for all valid hour/minute combinations") {
      val results = for
        h <- 0 to 23
        m <- 0 to 59
      yield Cron.dailyMake(h, m).exists { c =>
        val parts = c.render.split(" ")
        parts(0).toInt == m && parts(1).toInt == h && parts.length == 5
      }
      assertTrue(results.forall(identity))
    },

    test("weekly produces correct day-of-week field for all DayOfWeek values") {
      val results = DayOfWeek.values.map { d =>
        val rendered = Cron.weekly(d).render
        val parts    = rendered.split(" ")
        parts(4) == d.cronValue.toString
      }
      assertTrue(results.forall(identity))
    },

    test("hourly succeeds and round-trips for all valid minute values") {
      val results = (0 to 59).map { m =>
        Cron.hourlyMake(m).exists { c =>
          val parts = c.render.split(" ")
          parts(0).toInt == m && parts.length == 5
        }
      }
      assertTrue(results.forall(identity))
    },

    test("rejects all hours outside [0,23]") {
      val badHours = (-10 to -1) ++ (24 to 30)
      assertTrue(badHours.forall(h => Cron.dailyMake(h, 0).isLeft))
    },

    test("rejects all minutes outside [0,59]") {
      val badMinutes = (-10 to -1) ++ (60 to 70)
      assertTrue(badMinutes.forall(m => Cron.hourlyMake(m).isLeft))
    },
  )
end CronSpec
