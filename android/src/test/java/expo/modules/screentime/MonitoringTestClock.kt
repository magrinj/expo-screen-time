package expo.modules.screentime

import java.util.Calendar
import java.util.TimeZone

/** Fixed zones so the date tests do not depend on the machine's, as `scripts/swift-tests/main.swift` does with `utc` and `paris`. */
internal object MonitoringTestClock {
  val utc: TimeZone = TimeZone.getTimeZone("UTC")
  val paris: TimeZone = TimeZone.getTimeZone("Europe/Paris")
  val london: TimeZone = TimeZone.getTimeZone("Europe/London")

  /** Unix ms of a wall-clock instant, the twin of the Swift `date(_:_:_:_:_:_:_:)` helper. */
  fun date(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, zone: TimeZone = utc): Long {
    val calendar = Calendar.getInstance(zone)
    calendar.clear()
    calendar.set(y, mo - 1, d, h, mi, s)
    return calendar.timeInMillis
  }

  const val MINUTE = 60_000L
  const val HOUR = 3_600_000L
}
