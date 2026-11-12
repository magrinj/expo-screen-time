import DeviceActivity
import ManagedSettings
import Foundation
import UserNotifications

/// Shield Action Extension
/// Handles user interactions with the shield (blocking) screen: `close`,
/// `defer`, `unblock` (lift the pressed target until the next block),
/// `snooze` (lift the pressed target for a budget of its own use, metered by
/// a DeviceActivity this extension arms), and
/// `openApp` (bring the host app forward: iOS lets no extension launch an
/// app, so a local notification is posted for the user to tap).
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
    }

    /// A configured action, parsed from the stored JS shape.
    private enum ConfiguredAction: Equatable {
        case close
        case unblock
        case defer_
        case snooze(seconds: Int)
        case openApp
    }

    /// What `processAction` decided under the lock: a response, a snooze
    /// still to be granted — that one arms a DeviceActivity, a system call
    /// `BlockStateLock` must not span, so it runs after the critical section
    /// — or an open-app notification still to be posted (asynchronous, and
    /// no business of the lock either). `shieldTitle` is the title drawn on
    /// the shield the user pressed, for the notification to continue it.
    private enum Decision {
        case respond(ShieldActionResponse)
        case snooze(seconds: Int)
        case openApp(shieldTitle: String?)
    }

    /// The identifier of the open-app notification: one per host app, so a
    /// second press replaces the banner instead of stacking another.
    private static let openAppNotificationId = "expo_st_shield_open_app"

    // MARK: - Action Handlers

    /// Handle action for a shielded application
    override func handle(
        action: ShieldAction,
        for application: ApplicationToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        processAction(action, target: .application(application), completionHandler: completionHandler)
    }

    /// Handle action for a shielded web domain
    override func handle(
        action: ShieldAction,
        for webDomain: WebDomainToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        processAction(action, target: .webDomain(webDomain), completionHandler: completionHandler)
    }

    /// Handle action for a shielded application in a category
    override func handle(
        action: ShieldAction,
        for category: ActivityCategoryToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        processAction(action, target: .category(category), completionHandler: completionHandler)
    }

    // MARK: - Private Helpers

    /// Process a shield action and hand the appropriate response to
    /// `completionHandler` — synchronously, except for an `openApp` press,
    /// which responds once its notification is posted.
    private func processAction(_ action: ShieldAction, target: ShieldedTarget, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        let button: String
        switch action {
        case .primaryButtonPressed:
            button = "primary"
        case .secondaryButtonPressed:
            button = "secondary"
        @unknown default:
            completionHandler(.close)
            return
        }

        // The actions of the source that shields this target — a threshold,
        // else the latest schedule covering it, else the manual block — then
        // the default. Several sources can be active at once.
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

            // Record the blocked attempt — the pressed target and the
            // activity that owns its shield — for drainShieldInteractions().
            // This handler is the one callback that reliably fires per
            // shield interaction (the configuration data source is cached
            // by the system and not re-invoked on every open), so it
            // doubles as the "user hit a block" counter. Recorded whatever
            // the configured action turns out to be.
            defaults.appendShieldInteraction(button: button, target: token, activityName: owner?.kind.activityName)

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

            // An allowlist shields an app through `.all(except:)`, so when
            // the system reports the shield by its category alone there is
            // no way to single out the pressed app: nothing could be lifted
            // (`unblock`) and a snooze meter over the whole category would
            // spend its budget while the shield stays. Close instead; the
            // press is recorded above. If the system reports such a shield
            // by its application, the branches below apply as usual.
            if case .category = target, owner?.mode == .allowlist, action == .unblock || Self.isSnooze(action) {
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
            case .openApp:
                // The config the configuration extension drew for this
                // owner, so the banner reads as the shield's continuation.
                let config = owner?.kind.activityName.flatMap { defaults.getShieldConfig(activityName: $0) }
                    ?? defaults.getDefaultShieldConfig()
                return .openApp(shieldTitle: Self.title(in: config))
            }
        }

        switch decision {
        case .respond(let response):
            completionHandler(response)
        case .snooze(let seconds):
            completionHandler(snooze(target, requestedSeconds: seconds))
        case .openApp(let shieldTitle):
            postOpenAppNotification(shieldTitle: shieldTitle, completionHandler: completionHandler)
        }
    }

    /// The title text of a stored shield config: the nested shape
    /// (`{ title: { text } }`) or the legacy flat string. Nil when unset or
    /// blank.
    private static func title(in config: [String: Any]?) -> String? {
        var text: String?
        if let title = config?["title"] as? [String: Any] {
            text = title["text"] as? String
        } else {
            text = config?["title"] as? String
        }
        let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func isSnooze(_ action: ConfiguredAction) -> Bool {
        if case .snooze = action { return true }
        return false
    }

    /// The action configured for `button` ("primary" or "secondary").
    ///
    /// `setShieldActions` stores the JS shape — `primaryAction` /
    /// `secondaryAction`, each an action name or `{ type: "snooze", duration }`.
    /// A snooze
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
        case "openApp":
            return .openApp
        default:
            return nil
        }
    }

    // MARK: - Open app

    /// Bring the host app forward, the only way an extension can: post a
    /// local notification the user taps (tapping opens the host app; no
    /// handler is needed). The press was recorded above, so once open the
    /// app reads from `drainShieldInteractions()` which target the user
    /// wanted. `getCapabilities().shieldOpenApp` reports `"notification"`.
    ///
    /// Text: the title the shield shows (so the banner continues it), else
    /// none (iOS heads the banner with the host app's name already); the
    /// body names the host app and nothing else — never the blocked app.
    /// Posting needs the `notifications` permission
    /// step (requested with the `shieldOpenApp` feature); without it, or on
    /// any error, the press behaves like `close`: the user lands on the Home
    /// screen, and the recorded press still tells the app what happened.
    ///
    /// Response: `.close`, posted or not. `.defer` would keep the shield up
    /// under the banner, and a banner that never shows (Focus, a revoked
    /// permission) would leave the user stuck on a shield whose button does
    /// nothing visible; `.close` sends them to the Home screen with the
    /// banner on top. The completion is called exactly once — after the
    /// system answered, or after a short deadline should it not.
    private func postOpenAppNotification(shieldTitle: String?, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        let hostAppName = Self.hostAppName()
        let content = UNMutableNotificationContent()
        content.title = shieldTitle ?? ""
        content.body = hostAppName.map { "Tap to open \($0)." } ?? "Tap to continue."
        content.sound = .default
        let request = UNNotificationRequest(identifier: Self.openAppNotificationId, content: content, trigger: nil)

        let once = NSLock()
        var completed = false
        let finish = {
            once.lock()
            let first = !completed
            completed = true
            once.unlock()
            if first {
                completionHandler(.close)
            }
        }
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                NSLog("[expo-screen-time] openApp: could not post the notification (%@); the shield closes", error.localizedDescription)
            }
            finish()
        }
        DispatchQueue.global().asyncAfter(deadline: .now() + 3) {
            finish()
        }
    }

    /// The display name of the app this extension is embedded in
    /// (`<App>.app/PlugIns/<This>.appex`), for the notification text.
    private static func hostAppName() -> String? {
        let hostURL = Bundle.main.bundleURL.deletingLastPathComponent().deletingLastPathComponent()
        guard hostURL.pathExtension == "app", let host = Bundle(url: hostURL) else { return nil }
        for key in ["CFBundleDisplayName", "CFBundleName"] {
            if let name = host.object(forInfoDictionaryKey: key) as? String,
               !name.trimmingCharacters(in: .whitespaces).isEmpty {
                return name
            }
        }
        return nil
    }

    // MARK: - Snooze

    /// Lift the pressed target for `requestedSeconds` of its own use (at
    /// least `UsageMeter.stepSeconds`, like `temporaryUnlock`): record a
    /// `ShieldSnooze`, re-project every store (the target comes out of each
    /// store covering it; everything else stays shielded), then arm the
    /// snooze's meter (`SnoozeMeter`, shared with the app's
    /// `temporaryUnlock(…, { target })`, which is this grant made from the
    /// app). The monitor extension spends the budget and puts the target's
    /// shields back.
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

        // The snooze meters the system runs, read BEFORE the critical
        // section: a record always precedes its arm, so a meter registered
        // now whose snooze is not recorded under the lock is truly stale —
        // a meter listed after the lock may be one the app
        // (`temporaryUnlock(…, { target })`) recorded and armed in between.
        let registered = center.activities.filter { ShieldSnooze.isActivityName($0.rawValue) }

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
        let stale = registered.filter { !live.contains($0.rawValue) }
        if !stale.isEmpty {
            center.stopMonitoring(stale)
        }

        // 3. Arm the meter: a usage ladder over this target alone, from now
        // to the end of the day (`SnoozeMeter`).
        do {
            try SnoozeMeter.arm(snooze, over: target.shieldToken, on: center)
        } catch {
            NSLog("[expo-screen-time] snooze: could not arm the meter (%@); the shield stays", error.localizedDescription)
            lock.withLock {
                defaults.setSnoozes(defaults.getSnoozes().filter { $0.id != snooze.id })
                projectStores()
            }
            return .close
        }

        // 4. The app may have ended every snooze (`blockSelection`, `relock`,
        // `unblockAll`) or replaced this one (`temporaryUnlock(…, { target })`
        // on the same target) between the record and the arm: its stop
        // found no meter yet, so release this one, or it would hold a slot
        // for a snooze that no longer exists.
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
            if let shields = shields[name], !shields.isEmpty {
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
        return store.hasShields
    }

    private func lift(_ target: ShieldedTarget, in store: ManagedSettingsStore) {
        switch target {
        case .application(let token):
            if var applications = store.shield.applications {
                applications.remove(token)
                store.shield.applications = applications.isEmpty ? nil : applications
            }
            // The app may also be covered through its category — or, under
            // an allowlist, through `.all`.
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
            // Under an allowlist the web is shielded whole; the domain is excepted.
            switch store.shield.webDomainCategories {
            case .specific(let categories, var except)?:
                except.insert(token)
                store.shield.webDomainCategories = .specific(categories, except: except)
            case .all(var except)?:
                except.insert(token)
                store.shield.webDomainCategories = .all(except: except)
            default:
                break
            }

        case .category(let token):
            // Only a listed category can be lifted; `.all` has no "except
            // this category" (an allowlist shield is left in place).
            if case .specific(var categories, let except)? = store.shield.applicationCategories {
                categories.remove(token)
                store.shield.applicationCategories = categories.isEmpty
                    ? nil
                    : .specific(categories, except: except)
            }
        }
    }
}
