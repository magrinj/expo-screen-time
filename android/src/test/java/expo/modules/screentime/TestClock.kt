package expo.modules.screentime

import java.time.ZonedDateTime
import java.util.TimeZone

/** Fixed zones, so the date tests do not depend on the machine's zone (the Swift tests' `utc` / `paris`). */
internal val UTC: TimeZone = TimeZone.getTimeZone("UTC")
internal val PARIS: TimeZone = TimeZone.getTimeZone("Europe/Paris")

/** Unix ms of a wall-clock instant in [zone]; the Swift `ms(date(…))`. `java.time` is fine on the test JVM. */
internal fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, zone: TimeZone = UTC, ms: Int = 0): Long =
  ZonedDateTime.of(y, mo, d, h, mi, s, ms * 1_000_000, zone.toZoneId()).toInstant().toEpochMilli()

/** The engine's tick: [packageName] in the foreground with the screen on. */
internal fun using(atMs: Long, packageName: String) = UsageSample(atMs, packageName, screenInteractive = true)

/** The engine's tick with nothing metered in use: the launcher, or the screen off. */
internal fun idle(atMs: Long, foreground: String? = null, screenInteractive: Boolean = true) =
  UsageSample(atMs, foreground, screenInteractive)
