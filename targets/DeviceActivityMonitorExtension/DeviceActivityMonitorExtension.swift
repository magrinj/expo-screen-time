import DeviceActivity
import FamilyControls
import ManagedSettings
import Foundation

/// Device Activity Monitor Extension
/// Applies and removes blocks on schedule, records events, and meters the
/// temporary unlock and the shield snoozes.
///
/// Every block source has its own named store (BlockComposition): an interval
/// writes its activity's store when it starts and empties only that store
/// when it ends. Before this, every `intervalDidEnd` cleared the single store
/// the app and the extensions shared — the manual block included.
///
/// Callbacks arrive under the PHYSICAL name (`<activity><separator><generation>`,
/// see ActivityRegistration) and keep arriving for a registration after it
/// was stopped. A callback whose generation is not the one the activity's
/// config records is stale and ignored: stopping A then restarting A, or
/// re-registering A with a new schedule, used to let the old registration's
/// `intervalDidEnd` clear the new interval and its store.
///
/// Every callback runs as ONE critical section of `BlockStateLock`, the mutex
/// shared with the app and the shield extensions: the generation check, the
/// state writes and the store projection happen together, so a stale
/// callback that passed its check before the app re-registered can no longer
/// clear what the new generation wrote — the check and the writes are not
/// separable any more. The app puts the state in its final form under the
/// lock BEFORE it registers or stops a physical name, so a callback the
/// system then triggers takes the lock after the app released it and judges
/// the final state.
class DeviceActivityMonitorExtension: DeviceActivityMonitor {

    private let defaults = SharedDefaults.shared
    private let lock = BlockStateLock.shared

    /// Read per callback: this process can outlive a time-zone or calendar
    /// change, and a calendar captured at launch would keep the old one.
    private var calendar: Calendar { return Calendar.current }

    // MARK: - Temporary Unlock (internal activity)

    // Must mirror the constants in ExpoScreenTimeModule.swift.
    private let unlockActivityName = "expo_st_temporary_unlock"
    private let unlockStepPrefix = "expo_st_unlock_step_"

    // MARK: - Registrations

    private struct Registration {
        /// The logical name: events, markers, store and configs use it.
        let activityName: String
        let config: [String: Any]
    }

    /// The current registration a callback belongs to, or `nil` when the
    /// callback is stale (a stopped or replaced registration) or the activity
    /// was stopped before the callback arrived — the app recorded the end and
    /// cleared the state itself, or a newer registration owns them now. Call
    /// under the lock, in the critical section that acts on the answer.
    private func registration(for activity: DeviceActivityName) -> Registration? {
        guard let current = ActivityRegistration.currentRegistration(
            forPhysical: activity.rawValue,
            config: { defaults.getMonitoringConfig(activityName: $0) }
        ) else {
            return nil
        }
        return Registration(activityName: current.logical, config: current.config)
    }

    // MARK: - Interval Events

    /// Called when a scheduled interval starts (first device use after start time)
    override func intervalDidStart(for activity: DeviceActivityName) {
        // The unlock's and the snoozes' metering schedules are
        // module-internal: no monitoring config, no JS event, no block source.
        guard !Self.isMeter(activity) else { return }

        lock.withLock {
            guard let registration = registration(for: activity) else { return }
            let activityName = registration.activityName

            let now = Date()
            let calendar = self.calendar
            var alreadyStarted = defaults.getActiveInterval(activityName: activityName) != nil
            if let daily = Self.dailySchedule(of: registration.config) {
                let occurrenceStart = daily.latestStart(at: now, calendar: calendar)
                // One DeviceActivity per logical activity: an occurrence on an
                // unselected weekday is ignored entirely — no block, no event.
                guard daily.isSelected(occurrenceStart: occurrenceStart, calendar: calendar) else { return }
                // The module marks the interval BEFORE registering when the
                // schedule is registered inside its own occurrence, so this
                // callback — which fires within milliseconds — records nothing
                // for that occurrence; a marker from an earlier occurrence does
                // not count.
                alreadyStarted = defaults.getActiveInterval(activityName: activityName).map {
                    DailySchedule.isSameOccurrence(markedAt: $0.startedAt, occurrenceStart: occurrenceStart)
                } ?? false
            }

            if !alreadyStarted {
                defaults.setActiveInterval(activityName: activityName, startedAt: now.timeIntervalSince1970 * 1000)
                defaults.clearThresholdSelections(activityName: activityName)
                saveEvent(activityName: activityName, eventType: "intervalDidStart")
            }

            applyStore(forActivity: activityName)
        }
    }

    /// Called when a scheduled interval ends (first device use after end time)
    override func intervalDidEnd(for activity: DeviceActivityName) {
        guard activity.rawValue != unlockActivityName else {
            handleUnlockIntervalEnd()
            return
        }
        guard !ShieldSnooze.isActivityName(activity.rawValue) else {
            handleSnoozeIntervalEnd(activityName: activity.rawValue)
            return
        }

        lock.withLock {
            guard let registration = registration(for: activity) else { return }
            let activityName = registration.activityName

            // Recorded only for an occurrence that started: not on unselected
            // weekdays, and not when `stopMonitoring` already recorded it.
            if defaults.getActiveInterval(activityName: activityName) != nil {
                saveEvent(activityName: activityName, eventType: "intervalDidEnd")
            }

            // Forget the interval and the thresholds that fired inside it, and
            // empty THIS activity's store only — every other source keeps its
            // shields. Its shield owners are gone with it.
            defaults.clearActiveInterval(activityName: activityName)
            write(StoreShields(tokens: []), toStoreNamed: BlockStore.activity(activityName))
            defaults.pruneShieldOwners(ofActivity: activityName)
        }
    }

    // MARK: - Threshold Events

    /// Called when a usage threshold is reached
    override func eventDidReachThreshold(_ event: DeviceActivityEvent.Name, activity: DeviceActivityName) {
        let eventName = event.rawValue

        guard activity.rawValue != unlockActivityName else {
            handleUnlockStep(eventName: eventName)
            return
        }
        guard !ShieldSnooze.isActivityName(activity.rawValue) else {
            handleSnoozeStep(activityName: activity.rawValue, eventName: eventName)
            return
        }

        lock.withLock {
            guard let registration = registration(for: activity),
                  isSelectedOccurrence(registration.config) else {
                return
            }
            let activityName = registration.activityName

            // Record event
            saveEvent(activityName: activityName, eventType: "eventDidReachThreshold", eventName: eventName)

            // `blockOnThreshold` shields the EVENT's selection until the interval
            // ends. It lives in this activity's store, so it goes away with the
            // interval; the projection also leaves it unshielded while a
            // temporary unlock has suspended it for this occurrence.
            guard let events = registration.config["events"] as? [[String: Any]],
                  let eventConfig = events.first(where: { $0["eventName"] as? String == eventName }),
                  eventConfig["blockOnThreshold"] as? Bool == true,
                  let selectionId = eventConfig["selectionId"] as? String,
                  SelectionStore.shared.has(selectionId) else {
                return
            }

            defaults.appendThresholdSelection(activityName: activityName, selectionId: selectionId)
            applyStore(forActivity: activityName)
        }
    }

    // MARK: - Warning Events

    /// Called before interval ends (if warningTime is set)
    override func intervalWillEndWarning(for activity: DeviceActivityName) {
        guard !Self.isMeter(activity) else { return }
        lock.withLock {
            guard let registration = registration(for: activity),
                  isSelectedOccurrence(registration.config) else {
                return
            }
            saveEvent(activityName: registration.activityName, eventType: "intervalWillEndWarning")
        }
    }

    /// Called before threshold is reached
    override func eventWillReachThresholdWarning(
        _ event: DeviceActivityEvent.Name,
        activity: DeviceActivityName
    ) {
        guard !Self.isMeter(activity) else { return }
        lock.withLock {
            guard let registration = registration(for: activity),
                  isSelectedOccurrence(registration.config) else {
                return
            }
            saveEvent(activityName: registration.activityName, eventType: "eventWillReachThresholdWarning", eventName: event.rawValue)
        }
    }

    // MARK: - Temporary Unlock Handling

    /// A usage step fired: `<budget spent so far>` seconds of the blocked
    /// selection have been used since the unlock was granted. Record it, and
    /// re-apply the shields once the budget is spent.
    private func handleUnlockStep(eventName: String) {
        lock.withLock {
            guard eventName.hasPrefix(unlockStepPrefix),
                  let stepSeconds = Int(eventName.dropFirst(unlockStepPrefix.count)),
                  stepSeconds > 0 else {
                // Unrecognized event on the internal activity — fail closed:
                // ending the unlock early beats leaving the shields off forever.
                defaults.clearUnlockState()
                restoreActiveSources()
                return
            }

            // Sanity bound: measured usage can never exceed the wall-clock time
            // since the grant. iOS occasionally fires thresholds immediately on
            // registration (observed on iOS 26 betas) — such a fire claims more
            // usage than physically possible and must be ignored, or a fresh
            // grant would be consumed instantly.
            if let grantedAt = defaults.getUnlockGrantedAt(),
               !UsageMeter.isPlausible(step: stepSeconds, elapsedSeconds: Date().timeIntervalSince(grantedAt)) {
                return
            }

            // Steps can arrive out of order — consumption only moves forward.
            if stepSeconds > defaults.getUnlockConsumedSeconds() {
                defaults.setUnlockConsumedSeconds(stepSeconds)
            }

            let budget = defaults.getUnlockBudgetSeconds()
            if budget <= 0 || stepSeconds >= budget {
                defaults.clearUnlockState()
                restoreActiveSources()
            }
        }
    }

    /// The metering interval ended. When that genuinely happens (23:59:59)
    /// it's the daily reset: unspent budget expires at midnight. But
    /// `stopMonitoring` also fires this callback — on every re-grant or
    /// cancel, at any time of day — so only honor it near the boundary.
    private func handleUnlockIntervalEnd() {
        let components = calendar.dateComponents([.hour, .minute], from: Date())
        guard components.hour == 23, (components.minute ?? 0) >= 58 else { return }
        lock.withLock {
            defaults.clearUnlockState()
            restoreActiveSources()
        }
    }

    // MARK: - Shield Snooze Handling

    /// Whether `activity` is one of the module's meters (the unlock's or a
    /// snooze's), which never record events or shield anything.
    private static func isMeter(_ activity: DeviceActivityName) -> Bool {
        return ActivityRegistration.isInternalName(activity.rawValue)
    }

    /// A snooze meter's step fired: `<seconds>` of the snoozed target have
    /// been used since the grant. Record it; once the budget is spent,
    /// forget the snooze and put the target's shields back (every store is
    /// re-projected: the other snoozes and the unlock still subtract).
    private func handleSnoozeStep(activityName: String, eventName: String) {
        lock.withLock {
            let (snoozes, outcome) = ShieldSnoozes.recording(
                event: eventName,
                ofActivity: activityName,
                in: defaults.getSnoozes(),
                now: Date()
            )
            switch outcome {
            case .stale, .implausible:
                return
            case .consumed:
                defaults.setSnoozes(snoozes)
            case .spent, .malformed:
                defaults.setSnoozes(snoozes)
                restoreActiveSources()
            }
        }
        // The spent meter runs until its day ends; the next snooze or the
        // app's next launch releases its DeviceActivity slot (this extension
        // is not known to be allowed to stop activities).
    }

    /// A snooze meter's interval ended. Its interval runs to 23:59:59, so
    /// this is the day's end — unspent budget expires at midnight — unless
    /// the app stopped the meter, in which case it has already forgotten
    /// the snooze and there is nothing to do.
    private func handleSnoozeIntervalEnd(activityName: String) {
        lock.withLock {
            let (snoozes, ended) = ShieldSnoozes.ending(activityName: activityName, in: defaults.getSnoozes())
            guard ended else { return }
            defaults.setSnoozes(snoozes)
            restoreActiveSources()
        }
    }

    // MARK: - Stores

    /// Project EVERY store from the state — the manual block and every
    /// interval (plus its thresholds) still in progress, minus the snoozed
    /// targets — and empty every other store in the registry. Called once
    /// the unlock state is cleared, or a snooze ended. Call under the lock.
    private func restoreActiveSources() {
        let sources = defaults.activeBlockSources()
        let shields = BlockComposition.storeShields(of: sources, snoozed: defaults.snoozedTargets())
        let names = Set(defaults.shieldStoreNames()).union(shields.keys)
        for name in names {
            write(shields[name] ?? StoreShields(tokens: []), toStoreNamed: name)
        }
        defaults.pruneShieldOwners(keeping: sources.map(\.kind))
    }

    /// Project one activity's store from the state (its schedule selection
    /// plus any threshold selections, minus what a temporary unlock in
    /// progress suspended and the snoozed targets). Call under the lock.
    private func applyStore(forActivity activityName: String) {
        let shields = BlockComposition.storeShields(of: defaults.activeBlockSources(), snoozed: defaults.snoozedTargets())
        let name = BlockStore.activity(activityName)
        write(shields[name] ?? StoreShields(tokens: []), toStoreNamed: name)
    }

    /// Write `shields` into the store called `name`, keeping the shared
    /// registry in step so the app enumerates this store too. Call under the
    /// lock: the registry entry goes only when the projection that emptied
    /// the store ran under it.
    private func write(_ shields: StoreShields<ShieldToken>, toStoreNamed name: String) {
        let store = ManagedSettingsStore(named: ManagedSettingsStore.Name(name))
        if shields.tokens.isEmpty {
            store.setShields(StoreShields(tokens: []))
            defaults.unregisterShieldStore(name)
        } else {
            defaults.registerShieldStore(name)
            store.setShields(shields)
        }
    }

    // MARK: - Schedules

    /// Whether the occurrence in progress is on a selected weekday (always
    /// true for schedules without a weekday restriction, and for weekly
    /// multi-day intervals, which the system already restricts).
    private func isSelectedOccurrence(_ config: [String: Any]) -> Bool {
        guard let daily = Self.dailySchedule(of: config) else { return true }
        let calendar = self.calendar
        return daily.isSelected(occurrenceStart: daily.latestStart(at: Date(), calendar: calendar), calendar: calendar)
    }

    private static func dailySchedule(of config: [String: Any]) -> DailySchedule? {
        guard let scheduleDict = config["schedule"] as? [String: Any] else { return nil }
        return DailySchedule(scheduleDict: scheduleDict, weekdays: config["weekdays"] as? [Any])
    }

    // MARK: - Events

    /// Save an event to history
    private func saveEvent(activityName: String, eventType: String, eventName: String? = nil) {
        defaults.recordEvent(activityName: activityName, eventType: eventType, eventName: eventName)

        // Post Darwin notification to alert the main app
        postDarwinNotification()
    }

    /// Post a Darwin notification to notify the main app of a new event
    private func postDarwinNotification() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        CFNotificationCenterPostNotification(
            center,
            CFNotificationName("com.expo.screentime.monitorEvent" as CFString),
            nil,
            nil,
            true
        )
    }
}
