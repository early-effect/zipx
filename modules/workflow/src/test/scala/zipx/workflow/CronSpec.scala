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
    test("daily and hourly helpers") {
      assertTrue(
        Cron.daily(hour = 3, minute = 15).render == "15 3 * * *",
        Cron.hourly(minute = 45).render == "45 * * * *",
      )
    },
    test("raw escape hatch validates five fields") {
      assertTrue(Cron.raw("0 */6 * * *").render == "0 */6 * * *")
    },
    test("rejects out-of-range hour/minute") {
      val badHour   = scala.util.Try(Cron.daily(hour = 24).render)
      val badMinute = scala.util.Try(Cron.hourly(minute = 60).render)
      assertTrue(badHour.isFailure, badMinute.isFailure)
    },
    test("rejects malformed raw expressions") {
      val empty = scala.util.Try(Cron.raw("").render)
      val four  = scala.util.Try(Cron.raw("0 0 * *").render)
      assertTrue(empty.isFailure, four.isFailure)
    },
  )
end CronSpec
