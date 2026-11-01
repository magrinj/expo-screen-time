import DeviceActivity
import ManagedSettings
import Foundation

/// Shield Action Extension
/// Handles user interactions with the shield (blocking) screen: `close`,
/// `defer`, `unblock` (lift the pressed target until the next block), and
/// `snooze` (lift the pressed target for a budget of its own use, metered by
/// a DeviceActivity this extension arms — spike S1 showed it may).
class ShieldActionExtension: ShieldActionDelegate {

    private let defaults = SharedDefaults.shared
    private let lock = BlockStateLock.shared

    /// What the pressed shield covers — an `unblock` lifts exactly that.
    private enum ShieldedTarget {
        case application(ApplicationToken)
        case webDomain(WebDomainToken)
        case category(ActivityCategoryToken)

        /// The token as a block-source member, to find which source owns it.
        var shieldToken: ShieldToken {
            switch self {
            case .application(let token): return .application(token)
            case .webDomain(let token): return .webDomain(token)
            case .category(let token): return .category(token)
            }
        }

        /// The configuration context whose persisted owner this action may
        /// reuse (`ShieldOwnerMap`).
        var ownerContext: ShieldOwnerMap.Context {
            switch self {
            case .application: return .application
            case .webDomain: return .webDomain
            case .category: return .category
            }
        }

        /// A category's persisted owner must still shield through that
        /// category; an app's or a domain's is specific to it.
        var requiresCoverage: Bool {
            if case .category = self { return true }
            return false
        }

        /// The usage event that meters this target alone. Use before the
        /// grant does not count because the meter's interval starts at the
        /// press; a threshold iOS fires implausibly early is dropped by
        /// `UsageMeter.isPlausible`. NOT `includesPastActivity: false`: on
        /// iOS 26.6.1 a meter armed with it never fired (device, 2026-11-01;
        /// the same ladder without it re-shields after the budget).
        func usageEvent(threshold: DateComponents) -> DeviceActivityEvent {
            var applications = Set<ApplicationToken>()
            var categories = Set<ActivityCategoryToken>()
            var webDomains = Set<WebDomainToken>()
            switch self {
            case .application(let token): applications = [token]
            case .webDomain(let token): webDomains = [token]
            case .category(let token): categories = [token]
            }
            return DeviceActivityEvent(applications: applications, categories: categories, webDomains: webDomains, threshold: threshold)
        }
    }

    /// A configured action, parsed from the stored JS shape.
    private enum ConfiguredAction: Equatable {
        case close
        case unblock
        case defer_
        case snooze(seconds: Int)
    }

    /// What `processAction` decided under the lock: a response, or a snooze
    /// still to be granted — that one arms a DeviceActivity, a system call
    /// `BlockStateLock` must not span, so it runs after the critical section.
    private enum Decision {
        case respond(ShieldActionResponse)
        case snooze(seconds: Int)
    }

    // MARK: - Action Handlers

    /// Handle action for a shielded application
    override func handle(
        action: ShieldAction,
        for application: ApplicationToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        completionHandler(processAction(action, target: .application(application)))
    }

    /// Handle action for a shielded web domain
    override func handle(
        action: ShieldAction,
        for webDomain: WebDomainToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        completionHandler(processAction(action, target: .webDomain(webDomain)))
    }

    /// Handle action for a shielded application in a category
    override func handle(
        action: ShieldAction,
        for category: ActivityCategoryToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        completionHandler(processAction(action, target: .category(category)))
    }

    // MARK: - Private Helpers

    /// Process a shield action and return the appropriate response
    private func processAction(_ action: ShieldAction, target: ShieldedTarget) -> ShieldActionResponse {
        let button: String
        switch action {
        case .primaryButtonPressed:
            button = "primary"
        case .secondaryButtonPressed:
            button = "secondary"
        @unknown default:
            return .close
        }

        // Record the blocked attempt. This handler is the one callback that
        // reliably fires per shield interaction (the configuration data
        // source is cached by the system and not re-invoked on every open),
        // so it doubles as the "user hit a block" counter the app drains via
        // drainShieldInteractions().
        defaults.appendShieldInteraction(button: button)

        // The actions of the source that shields this target — a threshold,
        // else the latest schedule covering it, else the manual block — then
        // the default. Several sources can be active at once; a single
        // "active activity" pointer used to pick whichever started last.
        // The configuration extension resolved the owner with the app AND
        // its category and recorded it under the tokens it is valid for:
        // reuse it while that source is still active, so the buttons match
        // the screen. Suspended sources are not active.
        //
        // One critical section: the sources, the owner map, and the stores an
        // `unblock` edits — so neither a projection in another process nor
        // the registry pruning after the lift can interleave.
        let decision: Decision = lock.withLock {
            var actionConfig: [String: Any]?

            let token = target.shieldToken
            let resolvedByConfiguration = token.persistentKey.flatMap {
                defaults.getShieldOwnerMap().owner(forKey: $0, context: target.ownerContext)
            }
            let owner = BlockComposition.owner(
                ofAnyOf: [token],
                in: defaults.activeBlockSources(),
                preferring: resolvedByConfiguration,
                coveringTargets: target.requiresCoverage
            )
            if let activityName = owner?.kind.activityName {
                actionConfig = defaults.getShieldActions(activityName: activityName)
            }

            if actionConfig == nil {
                actionConfig = defaults.getDefaultShieldActions()
            }

            guard let action = Self.configuredAction(in: actionConfig, button: button) else {
                // Default: close the shield
                return .respond(.close)
            }

            switch action {
            case .close:
                // Close the shield, block remains active
                return .respond(.close)
            case .unblock:
                lift(target)
                return .respond(.close)
            case .defer_:
                // Keep the shield up and let it redraw; the press is recorded above.
                return .respond(.defer)
            case .snooze(let seconds):
                return .snooze(seconds: seconds)
            }
        }

        switch decision {
        case .respond(let response):
            return response
        case .snooze(let seconds):
            return snooze(target, requestedSeconds: seconds)
        }
    }

    /// The action configured for `button` ("primary" or "secondary").
    ///
    /// `setShieldActions` stores the JS shape — `primaryAction` /
    /// `secondaryAction`, each an action name or `{ type: "snooze", duration }`.
    /// This extension used to read `primaryButtonAction`, a key nothing writes,
    /// so every configured action silently fell back to `.close`. A snooze
    /// without a positive `duration` is misconfigured and closes (nothing
    /// is lifted for an unknown budget).
    private static func configuredAction(in config: [String: Any]?, button: String) -> ConfiguredAction? {
        guard let config = config else { return nil }
        var type: String?
        var duration: Int?
        if let name = config["\(button)Action"] as? String {
            type = name
        } else if let object = config["\(button)Action"] as? [String: Any] {
            type = object["type"] as? String
            duration = UsageMeter.seconds(object["duration"])
        } else {
            // Legacy native shape, kept readable.
            type = config["\(button)ButtonAction"] as? String
            duration = UsageMeter.seconds(config["\(button)ButtonSnoozeDuration"])
        }
        switch type {
        case "close":
            return .close
        case "unblock":
            return .unblock
        case "defer":
            return .defer_
        case "snooze":
            guard let duration = duration, duration > 0 else { return .close }
            return .snooze(seconds: duration)
        default:
            return nil
        }
    }

    // MARK: - Snooze

    /// Lift the pressed target for `requestedSeconds` of its own use (at
    /// least `UsageMeter.stepSeconds`, like `temporaryUnlock`): record a
    /// `ShieldSnooze`, re-project every store (the target comes out of each
    /// store covering it; everything else stays shielded), then arm the
    /// snooze's meter. The monitor extension spends the budget and puts the
    /// target's shields back.
    ///
    /// Order, per `BlockStateLock`'s rules: the state is final under the
    /// lock BEFORE the system is told, so the meter's first callbacks find
    /// the snooze. Arming can fail — at the 20-activity limit most likely —
    /// and then the snooze is taken back under the lock and the shield
    /// stays (fail closed, `.close`). A snooze over the same target is
    /// replaced by this one; snoozes over other targets run on.
    ///
    /// Response: `.defer` — the shield re-evaluates against the stores,
    /// where the target is no longer shielded, so it goes away and the
    /// user is in the app they asked for. `.close` would send them to the
    /// Home screen and make them open the app again (which the previous
    /// docs described). Should iOS keep a shield up after `.defer`, the
    /// device checklist of this fix says so and the choice is reversed.
    private func snooze(_ target: ShieldedTarget, requestedSeconds: Int) -> ShieldActionResponse {
        guard let encoded = target.shieldToken.encoded else { return .close }
        let center = DeviceActivityCenter()
        let snooze = ShieldSnooze(
            id: UUID().uuidString,
            tokenKind: encoded.kind,
            token: encoded.token,
            budgetSeconds: UsageMeter.budget(for: requestedSeconds),
            grantedAt: Date().timeIntervalSince1970 * 1000
        )

        // 1. Record and lift, under the lock.
        let live: Set<String> = lock.withLock {
            let (snoozes, _) = ShieldSnoozes.granting(snooze, in: defaults.currentSnoozes())
            defaults.setSnoozes(snoozes)
            projectStores()
            return Set(snoozes.map(\.activityName))
        }

        // 2. Release the slots of meters without a snooze: the one this
        // snooze replaced, and those of snoozes that were spent (the
        // monitor extension cannot stop them), expired at midnight, or
        // ended by the app while it could not reach the system. Each is
        // one of the 20 DeviceActivity slots, and this snooze needs one.
        let stale = center.activities.filter { ShieldSnooze.isActivityName($0.rawValue) && !live.contains($0.rawValue) }
        if !stale.isEmpty {
            center.stopMonitoring(stale)
        }

        // 3. Arm the meter: a usage ladder over this target alone, from now
        // to the end of the day (a budget never carries across midnight;
        // the interval's end is the monitor's day-end callback) — or past
        // it when the day has less than the system's minimum interval left
        // (`UsageMeter.meterEnd`): an end before the start means "tomorrow".
        var events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
        for seconds in UsageMeter.thresholds(budget: snooze.budgetSeconds) {
            events[DeviceActivityEvent.Name(ShieldSnooze.stepEventName(seconds))] = target.usageEvent(
                threshold: DateComponents(hour: seconds / 3600, minute: (seconds % 3600) / 60, second: seconds % 60)
            )
        }
        let calendar = Calendar.current
        let now = Date()
        let schedule = DeviceActivitySchedule(
            intervalStart: calendar.dateComponents([.hour, .minute, .second], from: now),
            intervalEnd: calendar.dateComponents([.hour, .minute, .second], from: UsageMeter.meterEnd(now: now, calendar: calendar)),
            repeats: false
        )
        do {
            try center.startMonitoring(DeviceActivityName(snooze.activityName), during: schedule, events: events)
        } catch {
            NSLog("[expo-screen-time] snooze: could not arm the meter (%@); the shield stays", error.localizedDescription)
            lock.withLock {
                defaults.setSnoozes(defaults.getSnoozes().filter { $0.id != snooze.id })
                projectStores()
            }
            return .close
        }

        // 4. The app may have ended every snooze (`blockSelection`, `relock`,
        // `unblockAll`) between the record and the arm: its stop found no
        // meter yet, so release this one, or it would hold a slot for a
        // snooze that no longer exists.
        let stillRecorded = lock.withLock { defaults.getSnoozes().contains { $0.id == snooze.id } }
        if !stillRecorded {
            center.stopMonitoring([DeviceActivityName(snooze.activityName)])
            return .close
        }
        return .defer
    }

    /// Project EVERY store from the state — the sources in force, minus
    /// the unlock suspension and the snoozed targets — keeping the registry
    /// in step. Call under the lock.
    private func projectStores() {
        let sources = defaults.activeBlockSources()
        let shields = BlockComposition.storeShields(of: sources, snoozed: defaults.snoozedTargets())
        for name in Set(defaults.shieldStoreNames()).union(shields.keys) {
            let store = ManagedSettingsStore(named: ManagedSettingsStore.Name(name))
            if let shields = shields[name] {
                defaults.registerShieldStore(name)
                store.setShields(shields)
            } else {
                store.setShields(StoreShields(tokens: []))
                defaults.unregisterShieldStore(name)
            }
        }
        defaults.pruneShieldOwners(keeping: sources.map(\.kind))
    }

    // MARK: - Unblock

    /// Lift the shield on exactly what was pressed, in every store that
    /// shields it (the manual block and each activity in progress), until a
    /// block is applied again (the next `blockSelection`, relock, or interval
    /// start). Call under the lock: a store leaves the registry only when
    /// the lift that emptied it ran under it.
    private func lift(_ target: ShieldedTarget) {
        // Every store that may hold shields — the registry every writer
        // maintains, not the monitoring configs (which miss a store written
        // by a late threshold callback).
        for name in defaults.shieldStoreNames() {
            let store = ManagedSettingsStore(named: ManagedSettingsStore.Name(name))
            lift(target, in: store)
            if !Self.hasShields(store) {
                defaults.unregisterShieldStore(name)
            }
        }
    }

    private static func hasShields(_ store: ManagedSettingsStore) -> Bool {
        return !(store.shield.applications?.isEmpty ?? true) ||
            (store.shield.applicationCategories.map { $0 != ShieldSettings.ActivityCategoryPolicy.none } ?? false) ||
            !(store.shield.webDomains?.isEmpty ?? true)
    }

    private func lift(_ target: ShieldedTarget, in store: ManagedSettingsStore) {
        switch target {
        case .application(let token):
            if var applications = store.shield.applications {
                applications.remove(token)
                store.shield.applications = applications.isEmpty ? nil : applications
            }
            // The app may also be covered through its category.
            switch store.shield.applicationCategories {
            case .specific(let categories, var except)?:
                except.insert(token)
                store.shield.applicationCategories = .specific(categories, except: except)
            case .all(var except)?:
                except.insert(token)
                store.shield.applicationCategories = .all(except: except)
            default:
                break
            }

        case .webDomain(let token):
            if var webDomains = store.shield.webDomains {
                webDomains.remove(token)
                store.shield.webDomains = webDomains.isEmpty ? nil : webDomains
            }

        case .category(let token):
            if case .specific(var categories, let except)? = store.shield.applicationCategories {
                categories.remove(token)
                store.shield.applicationCategories = categories.isEmpty
                    ? nil
                    : .specific(categories, except: except)
            }
        }
    }
}
