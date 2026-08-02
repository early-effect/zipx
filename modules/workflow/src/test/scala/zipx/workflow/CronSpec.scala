package zipx.workflow

import zio.test.*

object CronSpec extends ZIOSpecDefault:

  def spec = suite("Cron")(
    // --- Weekly ---
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

    // --- Daily / Hourly ---
    test("daily and hourly helpers") {
      assertTrue(
        Cron.daily(hour = 3, minute = 15).render == "15 3 * * *",
        Cron.hourly(minute = 45).render == "45 * * * *",
      )
    },

    // --- Boundary values (should succeed) ---
    test("accepts max valid hour and minute") {
      assertTrue(
        Cron.daily(hour = 23, minute = 59).render == "59 23 * * *",
        Cron.hourly(minute = 59).render == "59 * * * *",
      )
    },

    // --- Rejection: out-of-range hour/minute ---
    test("rejects out-of-range hour") {
      val under = scala.util.Try(Cron.daily(hour = -1).render)
      val over  = scala.util.Try(Cron.daily(hour = 24).render)
      assertTrue(under.isFailure, over.isFailure)
    },
    test("rejects out-of-range minute") {
      val under = scala.util.Try(Cron.hourly(minute = -1).render)
      val over  = scala.util.Try(Cron.hourly(minute = 60).render)
      assertTrue(under.isFailure, over.isFailure)
    },

    // --- Raw: happy path ---
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
      assertTrue(exprs.map(Cron.raw(_).render) == exprs)
    },

    // --- Raw: rejection ---
    test("rejects empty raw expression") {
      val empty = scala.util.Try(Cron.raw("").render)
      val ws    = scala.util.Try(Cron.raw("   ").render)
      assertTrue(empty.isFailure, ws.isFailure)
    },
    test("rejects raw expressions with wrong number of fields") {
      val four = scala.util.Try(Cron.raw("0 0 * *").render)
      val six  = scala.util.Try(Cron.raw("0 0 * * * *").render)
      assertTrue(four.isFailure, six.isFailure)
    },

    // --- Property-based: exhaustive range checks ---
    test("daily succeeds and round-trips for all valid hour/minute combinations") {
      val results = for
        h <- 0 to 23
        m <- 0 to 59
      yield
        val c        = Cron.daily(hour = h, minute = m)
        val rendered = c.render
        val parts    = rendered.split(" ")
        (parts(0).toInt == m && parts(1).toInt == h && parts.length == 5)
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
        val c        = Cron.hourly(minute = m)
        val rendered = c.render
        val parts    = rendered.split(" ")
        (parts(0).toInt == m && parts.length == 5)
      }
      assertTrue(results.forall(identity))
    },

    test("rejects all hours outside [0,23]") {
      val badHours = (-10 to -1) ++ (24 to 30)
      assertTrue(badHours.map(h => scala.util.Try(Cron.daily(hour = h).render).isFailure).forall(identity))
    },

    test("rejects all minutes outside [0,59]") {
      val badMinutes = (-10 to -1) ++ (60 to 70)
      assertTrue(badMinutes.map(m => scala.util.Try(Cron.hourly(minute = m).render).isFailure).forall(identity))
    },
  )
end CronSpec
