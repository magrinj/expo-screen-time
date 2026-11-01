import Foundation

/// A time-of-day interval repeated every day, optionally restricted to
/// weekdays (1 = Sunday … 7 = Saturday, like `DateComponents.weekday`). An
/// interval whose end is not after its start crosses midnight, and an
/// occurrence belongs to the weekday on which it STARTS — a Monday 22:00–06:00
/// occurrence is still Monday's at 01:00 on Tuesday.
///
/// One DeviceActivity is registered per logical activity; the monitor
/// extension uses this type to skip occurrences on unselected weekdays, and
/// `startMonitoring` uses it to apply the block synchronously when it is
/// called inside an occurrence (the system's `intervalDidStart` is
/// asynchronous). Pure Foundation, unit-tested in `scripts/swift-tests`.
/// Copied verbatim into the monitor extension — keep the copies identical.
struct DailySchedule: Equatable {
    struct TimeOfDay: Equatable {
        var hour: Int
        var minute: Int
        var second: Int

        var secondsSinceMidnight: Int {
            return hour * 3600 + minute * 60 + second
        }
    }

    var start: TimeOfDay
    var end: TimeOfDay
    /// Selected weekdays; empty means every day.
    var weekdays: Set<Int>

    /// `end` at or before `start` means the occurrence ends the next day
    /// (equal bounds: a 24 h occurrence).
    var crossesMidnight: Bool {
        return end.secondsSinceMidnight <= start.secondsSinceMidnight
    }

    init(start: TimeOfDay, end: TimeOfDay, weekdays: Set<Int> = []) {
        self.start = start
        self.end = end
        self.weekdays = weekdays
    }

    /// From the JS `schedule` (`intervalStart` / `intervalEnd` date components)
    /// and the optional `weekdays` array, as stored in the monitoring config.
    ///
    /// Returns `nil` when the components describe something that is not a
    /// daily interval: a weekly interval whose start and end fall on different
    /// weekdays. A `weekday` set on the components makes the interval weekly
    /// and takes precedence over the `weekdays` array.
    init?(scheduleDict: [String: Any], weekdays: [Any]?) {
        guard let startDict = scheduleDict["intervalStart"] as? [String: Any],
              let endDict = scheduleDict["intervalEnd"] as? [String: Any],
              let startHour = Self.intValue(startDict["hour"]),
              let endHour = Self.intValue(endDict["hour"]) else {
            return nil
        }
        start = TimeOfDay(
            hour: startHour,
            minute: Self.intValue(startDict["minute"]) ?? 0,
            second: Self.intValue(startDict["second"]) ?? 0
        )
        end = TimeOfDay(
            hour: endHour,
            minute: Self.intValue(endDict["minute"]) ?? 0,
            second: Self.intValue(endDict["second"]) ?? 0
        )

        let startWeekday = Self.intValue(startDict["weekday"])
        let endWeekday = Self.intValue(endDict["weekday"])
        if let startWeekday = startWeekday {
            if let endWeekday = endWeekday, endWeekday != startWeekday { return nil }
            self.weekdays = [startWeekday]
        } else if endWeekday != nil {
            return nil
        } else {
            self.weekdays = Self.weekdays(from: weekdays)
        }
    }

    /// Normalizes a JS `weekdays` array: integers 1…7 only, duplicates dropped.
    static func weekdays(from values: [Any]?) -> Set<Int> {
        return Set((values ?? []).compactMap(intValue).filter { (1...7).contains($0) })
    }

    /// An integer from a JS value. JS numbers reach Swift as `Double` inside
    /// `[String: Any]`, and `Double as? Int` is always nil; values read back
    /// from UserDefaults are `NSNumber`s.
    static func intValue(_ value: Any?) -> Int? {
        switch value {
        case let int as Int:
            return int
        case let double as Double where double.rounded() == double && abs(double) < Double(Int.max):
            return Int(double)
        case let number as NSNumber:
            return number.intValue
        default:
            return nil
        }
    }

    // MARK: - Occurrences

    /// Start of the most recent occurrence that began at or before `now`:
    /// today's start if it has passed, otherwise yesterday's.
    func latestStart(at now: Date, calendar: Calendar) -> Date {
        let todayStart = Self.wallClock(start, onDayOf: now, calendar: calendar)
        if todayStart <= now { return todayStart }
        let yesterday = calendar.date(byAdding: .day, value: -1, to: now) ?? now.addingTimeInterval(-86_400)
        return Self.wallClock(start, onDayOf: yesterday, calendar: calendar)
    }

    /// End of the occurrence that starts at `occurrenceStart`.
    func end(ofOccurrenceStarting occurrenceStart: Date, calendar: Calendar) -> Date {
        let endDay = crossesMidnight
            ? (calendar.date(byAdding: .day, value: 1, to: occurrenceStart) ?? occurrenceStart.addingTimeInterval(86_400))
            : occurrenceStart
        return Self.wallClock(end, onDayOf: endDay, calendar: calendar)
    }

    /// The occurrence in progress at `now` (start ≤ now < end), ignoring
    /// weekdays.
    func occurrence(at now: Date, calendar: Calendar) -> DateInterval? {
        let occurrenceStart = latestStart(at: now, calendar: calendar)
        let occurrenceEnd = end(ofOccurrenceStarting: occurrenceStart, calendar: calendar)
        guard now < occurrenceEnd else { return nil }
        return DateInterval(start: occurrenceStart, end: occurrenceEnd)
    }

    /// Whether the occurrence starting at `occurrenceStart` is on a selected
    /// weekday.
    func isSelected(occurrenceStart: Date, calendar: Calendar) -> Bool {
        guard !weekdays.isEmpty else { return true }
        return weekdays.contains(calendar.component(.weekday, from: occurrenceStart))
    }

    /// Whether the schedule blocks right now: inside an occurrence that is on
    /// a selected weekday.
    func isActive(at now: Date, calendar: Calendar) -> Bool {
        guard let occurrence = occurrence(at: now, calendar: calendar) else { return false }
        return isSelected(occurrenceStart: occurrence.start, calendar: calendar)
    }

    /// Whether an interval marker written at `markedAt` (Unix ms) belongs to
    /// the occurrence that started at `occurrenceStart`. The module marks the
    /// interval when it applies a block synchronously; the system's
    /// `intervalDidStart` then arrives for the same occurrence and must not
    /// record a second event.
    static func isSameOccurrence(markedAt: Double, occurrenceStart: Date) -> Bool {
        return markedAt >= occurrenceStart.timeIntervalSince1970 * 1000
    }

    /// What `startMonitoring` does with the block right now, given the stored
    /// schedule. "Cannot compute" is distinct from "outside": a weekly interval
    /// spanning weekdays (Fri 22:00 → Sun 06:00) is not a daily schedule, and
    /// the module used to take its `nil` for "outside" and clear the marker and
    /// store the system's own callback may already have applied.
    enum SyncDecision: Equatable {
        /// Inside an occurrence on a selected weekday: mark the interval (from
        /// `occurrenceStart`) and apply the store now.
        case apply(occurrenceStart: Date)
        /// Outside, or on an unselected weekday: nothing from a previous
        /// registration may linger.
        case clear
        /// Not a daily schedule: leave the marker and the store to the monitor.
        case leaveToMonitor
    }

    static func syncDecision(scheduleDict: [String: Any], weekdays: [Any]?, now: Date, calendar: Calendar) -> SyncDecision {
        guard let daily = DailySchedule(scheduleDict: scheduleDict, weekdays: weekdays) else { return .leaveToMonitor }
        guard daily.isActive(at: now, calendar: calendar) else { return .clear }
        return .apply(occurrenceStart: daily.latestStart(at: now, calendar: calendar))
    }

    /// `time` on the calendar day containing `day`.
    private static func wallClock(_ time: TimeOfDay, onDayOf day: Date, calendar: Calendar) -> Date {
        return calendar.date(bySettingHour: time.hour, minute: time.minute, second: time.second, of: day)
            ?? calendar.startOfDay(for: day).addingTimeInterval(TimeInterval(time.secondsSinceMidnight))
    }
}
