import Foundation

/// How a logical activity (the `activityName` JS uses everywhere) maps to the
/// physical `DeviceActivityName` registered with the system.
///
/// Every registration gets a generation, and the physical name is
/// `<logical><separator><generation>`. The system keeps delivering callbacks
/// for a registration after it was stopped — `stopMonitoring` triggers its
/// `intervalDidEnd` almost immediately — so stopping A and restarting A, or
/// re-registering A to change its schedule, would let the OLD
/// registration's end callback clear the NEW interval and its store. The
/// monitor extension honours a callback only when its generation is the
/// one recorded in the logical activity's monitoring config; everything else
/// is stale and ignored. Events, configs, store names, `getMonitoredActivities`
/// and `stopMonitoring` keep using the logical name.
///
/// The separator is a sequence no activity name contains in practice;
/// `startMonitoring` rejects names that do, which makes the mapping exact.
///
/// Builds before this registered the logical name itself and, for `weekdays`,
/// one `<name>_weekday_<n>` variant per day. Those legacy registrations keep
/// firing after an upgrade until the activity is re-registered or stopped, so
/// they resolve too (generation `nil`), through the variant config's
/// `_parentActivity` — never by prefix: a real activity called `A_weekday_2`
/// is not a variant of `A`.
///
/// Pure Foundation, unit-tested in `scripts/swift-tests`. Copied verbatim into
/// the monitor extension — keep the copies identical.
enum ActivityRegistration {
    /// Between the logical name and the generation in a physical name.
    static let generationSeparator = "__expo_st_gen_"

    /// Config key holding the generation of the current registration.
    static let generationKey = "_generation"
    /// Legacy variant config → its parent (`<parent>_weekday_<n>`).
    static let legacyParentKey = "_parentActivity"
    /// Legacy parent config → its variant names.
    static let legacyVariantsKey = "_weekdayActivities"
    static let legacyWeekdayInfix = "_weekday_"

    /// Names starting with this are the module's own (the temporary-unlock
    /// meter, `expo_st_temporary_unlock`, and the shield-snooze meters,
    /// `expo_st_snooze_<id>`) and never a user activity: a user could
    /// otherwise register one that `getMonitoredActivities` hides, or stop
    /// a meter through `stopMonitoring`.
    static let reservedPrefix = "expo_st_"

    /// Whether `name` can be a logical activity name: not empty, not
    /// reserved, and free of the generation separator. The same rule gates
    /// `startMonitoring` and `stopMonitoring`.
    static func isValidLogicalName(_ name: String) -> Bool {
        return !name.isEmpty && !name.hasPrefix(reservedPrefix) && !name.contains(generationSeparator)
    }

    /// Whether `name` belongs to the module (the unlock and snooze meters):
    /// never listed by, nor stopped through, the monitoring API.
    static func isInternalName(_ name: String) -> Bool {
        return name.hasPrefix(reservedPrefix)
    }

    static func physicalName(logical: String, generation: Int) -> String {
        return logical + generationSeparator + String(generation)
    }

    struct Parsed: Equatable {
        var logical: String
        /// `nil` for a registration from a build before generations.
        var generation: Int?
    }

    /// Split a physical name. A name without a well-formed generation suffix
    /// is a legacy registration of the whole string.
    static func parse(_ physical: String) -> Parsed {
        guard let range = physical.range(of: generationSeparator, options: .backwards) else {
            return Parsed(logical: physical, generation: nil)
        }
        let suffix = physical[range.upperBound...]
        guard !suffix.isEmpty, suffix.allSatisfy(\.isNumber), let generation = Int(suffix) else {
            return Parsed(logical: physical, generation: nil)
        }
        return Parsed(logical: String(physical[..<range.lowerBound]), generation: generation)
    }

    /// The generation recorded in a monitoring config; `nil` for a legacy one.
    static func generation(of config: [String: Any]) -> Int? {
        return DailySchedule.intValue(config[generationKey])
    }

    /// Exactly `<parent>_weekday_<n>`, n in 1…7.
    static func isLegacyWeekdayVariant(_ name: String, of parent: String) -> Bool {
        let prefix = parent + legacyWeekdayInfix
        guard name.hasPrefix(prefix) else { return false }
        let rest = name.dropFirst(prefix.count)
        guard rest.count == 1, let weekday = Int(rest) else { return false }
        return (1...7).contains(weekday)
    }

    /// The variants a legacy parent config lists — validated, so that only
    /// what that build registered is treated as a variant.
    static func legacyWeekdayVariants(in config: [String: Any]?, of parent: String) -> [String] {
        guard let listed = config?[legacyVariantsKey] as? [String] else { return [] }
        return listed.filter { isLegacyWeekdayVariant($0, of: parent) }
    }

    /// Whether the config stored under `variant` is still the legacy
    /// variant state of `parent` — no generation, and naming `parent` — and
    /// may be deleted with it. A later `startMonitoring("A_weekday_2")` makes
    /// that name a live activity of its own, with a generation: the legacy
    /// list of `A` must not take its config and selection down.
    static func isLegacyVariantState(_ config: [String: Any]?, variant: String, of parent: String) -> Bool {
        guard let config = config,
              generation(of: config) == nil,
              config[legacyParentKey] as? String == parent else {
            return false
        }
        return isLegacyWeekdayVariant(variant, of: parent)
    }

    /// The current registration a callback for `physical` belongs to, or `nil`
    /// when the callback is stale: its generation is not the one in the
    /// logical activity's config, or that activity is no longer configured.
    /// `config` looks a monitoring config up by activity name.
    static func currentRegistration(
        forPhysical physical: String,
        config lookup: (String) -> [String: Any]?
    ) -> (logical: String, config: [String: Any])? {
        let parsed = parse(physical)
        if let generation = parsed.generation {
            guard let config = lookup(parsed.logical), self.generation(of: config) == generation else { return nil }
            return (parsed.logical, config)
        }

        // Legacy: `<name>`, or `<name>_weekday_<n>` whose own config names its parent.
        var logical = physical
        if let variantConfig = lookup(physical),
           let parent = variantConfig[legacyParentKey] as? String,
           isLegacyWeekdayVariant(physical, of: parent) {
            logical = parent
        }
        guard let config = lookup(logical), generation(of: config) == nil else { return nil }
        return (logical, config)
    }

    /// The interval a legacy build's `expo_st_active_activity` pointer (the
    /// physical name of the schedule in progress) describes: the logical
    /// activity and when its occurrence started — from the daily schedule
    /// when there is one, else `now`. `nil` when that activity is not
    /// configured any more, or when its daily schedule says the pointer is
    /// stale (now is outside any selected occurrence).
    static func legacyActiveInterval(
        pointer physical: String,
        config lookup: (String) -> [String: Any]?,
        now: Date,
        calendar: Calendar
    ) -> (logical: String, startedAt: Double)? {
        let logical = logicalName(ofRegistered: physical, config: lookup)
        guard let config = lookup(logical) else { return nil }
        var startedAt = now.timeIntervalSince1970 * 1000
        if let scheduleDict = config["schedule"] as? [String: Any],
           let daily = DailySchedule(scheduleDict: scheduleDict, weekdays: config["weekdays"] as? [Any]) {
            guard let occurrence = daily.occurrence(at: now, calendar: calendar),
                  daily.isSelected(occurrenceStart: occurrence.start, calendar: calendar) else {
                return nil
            }
            startedAt = occurrence.start.timeIntervalSince1970 * 1000
        }
        return (logical, startedAt)
    }

    /// The logical name a registered physical name is reported under by
    /// `getMonitoredActivities`.
    static func logicalName(ofRegistered physical: String, config lookup: (String) -> [String: Any]?) -> String {
        let parsed = parse(physical)
        if parsed.generation != nil { return parsed.logical }
        if let variantConfig = lookup(physical),
           let parent = variantConfig[legacyParentKey] as? String,
           isLegacyWeekdayVariant(physical, of: parent) {
            return parent
        }
        return physical
    }

    // MARK: - The activity limit

    /// How many logical activities `startMonitoring` accepts at once —
    /// `limits.maxMonitoredActivities`, enforced identically on both
    /// platforms: iOS caps DeviceActivity at 20 per app and the
    /// temporary-unlock meter holds one. Checked by the module before the
    /// system is asked: the system only refuses the 21st registration, so
    /// a 20th logical activity would go through whenever no meter or
    /// snooze held its slot — and take the slot the next unlock or snooze
    /// needs.
    static let maxLogicalActivities = 19

    /// The logical activities the system's registrations amount to: every
    /// generation of a name is that name, a legacy weekday variant is its
    /// parent, and the module's own meters are not activities.
    static func logicalNames(ofRegistered physicals: [String], config lookup: (String) -> [String: Any]?) -> Set<String> {
        var names = Set<String>()
        for physical in physicals where !isInternalName(physical) {
            names.insert(logicalName(ofRegistered: physical, config: lookup))
        }
        return names
    }

    /// Whether registering `activityName` would exceed the limit, given the
    /// logical names registered. Re-registering one of them replaces it and
    /// costs nothing.
    static func exceedsLimit(registering activityName: String, among registered: Set<String>, limit: Int = maxLogicalActivities) -> Bool {
        return !registered.contains(activityName) && registered.count >= limit
    }

    // MARK: - Replacing a registration

    /// What `startMonitoring` does to the activity's interval state — under
    /// the lock, BEFORE the new generation is registered — given what the
    /// new schedule says about now and the interval the previous
    /// registration had in progress.
    struct IntervalPlan: Equatable {
        enum Marker: Equatable {
            /// Keep the previous marker as it is (`lifted` included).
            case keep
            /// Mark the interval in progress since `startedAt` (ms), not lifted.
            case set(startedAt: Double)
            /// No interval in progress.
            case clear
        }

        var marker: Marker
        /// Keep the threshold selections that fired; else they are reset
        /// (a fresh registration re-arms its thresholds).
        var keepsThresholds: Bool
        var recordsStart: Bool
        var recordsEnd: Bool
    }

    /// The plan for a NEW registration replacing `previous` (`restoring`
    /// false), or for putting `previous` back after the new one failed
    /// (`restoring` true).
    ///
    /// - `.apply`: the interval is in progress. The same occurrence as the
    ///   previous marker keeps its start (the shields never drop and no
    ///   `intervalDidEnd`/`intervalDidStart` pair is recorded; a new
    ///   registration un-lifts it, a restore keeps it as it was). Any other
    ///   marker is a previous occurrence whose end was missed: it ends here
    ///   and the new one starts now.
    /// - `.clear`: outside, or an unselected weekday. The previous interval,
    ///   if any, ends here.
    /// - `.leaveToMonitor`: a schedule this process cannot evaluate (a weekly
    ///   interval spanning weekdays). A new registration clears the previous
    ///   interval — it belonged to the previous schedule, and a daily
    ///   activity replaced by a multi-day one kept shielding from the stale
    ///   marker — and the monitor's `intervalDidStart` applies the new one
    ///   (within milliseconds when now is inside). A restore keeps what the
    ///   previous registration had.
    static func intervalPlan(
        for decision: DailySchedule.SyncDecision,
        previous: ActiveInterval?,
        now: Date,
        restoring: Bool
    ) -> IntervalPlan {
        switch decision {
        case .apply(let occurrenceStart):
            if let previous = previous, DailySchedule.isSameOccurrence(markedAt: previous.startedAt, occurrenceStart: occurrenceStart) {
                return IntervalPlan(
                    marker: restoring ? .keep : .set(startedAt: previous.startedAt),
                    keepsThresholds: restoring,
                    recordsStart: false,
                    recordsEnd: false
                )
            }
            return IntervalPlan(
                marker: .set(startedAt: now.timeIntervalSince1970 * 1000),
                keepsThresholds: false,
                recordsStart: true,
                recordsEnd: previous != nil
            )
        case .clear:
            return IntervalPlan(marker: .clear, keepsThresholds: false, recordsStart: false, recordsEnd: previous != nil)
        case .leaveToMonitor:
            if restoring {
                return IntervalPlan(marker: .keep, keepsThresholds: true, recordsStart: false, recordsEnd: false)
            }
            return IntervalPlan(marker: .clear, keepsThresholds: false, recordsStart: false, recordsEnd: previous != nil)
        }
    }

    /// The config put back when a registration is rolled back after the
    /// previous one was released (the 20-activity limit): the previous
    /// settings under a FRESH generation. The released registration keeps
    /// calling back for a moment; re-registering under its old generation
    /// would make those stale callbacks current again and let its
    /// `intervalDidEnd` clear the restored interval. Legacy weekday variants
    /// cannot be re-registered; the parent covers their days through
    /// `weekdays`.
    static func rollbackConfig(previous: [String: Any], generation: Int) -> [String: Any] {
        var config = previous
        config[generationKey] = generation
        config.removeValue(forKey: legacyVariantsKey)
        return config
    }
}
