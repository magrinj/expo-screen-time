import DeviceActivity
import FamilyControls
import Foundation
import ManagedSettings
import ScreenTime
import SwiftUI
import Combine
import ExpoModulesCore

public class ExpoScreenTimeModule: Module {
    public let screenTimeManager = ScreenTimeManager()
    private let sharedDefaults = SharedDefaults.shared
    /// The instance registered with Expo's permission service, which looks it
    /// up by class: the request options are handed to it before each request.
    private let permissionRequester = ScreenTimePermissionRequester()

    // Event listening state. The cursor and timer are only touched on the
    // main queue (the Darwin callback and the poll timer both run there).
    private var isListening = false
    private var eventCursor = MonitorEventCursor(startingAt: .infinity)
    private var pollTimer: Timer?

    public func definition() -> ModuleDefinition {
        Name("ExpoScreenTime")

        // Event for real-time monitor notifications
        Events("onMonitorEvent")

        OnCreate {
            if #available(iOS 16.0, *) {
                _ = AuthorizationCenter.shared.authorizationStatus
            }

            self.appContext?.permissions?.register([
                self.permissionRequester
            ])

            // Builds before block composition kept every shield in the unnamed
            // store and one global "active activity" pointer: moved once.
            self.screenTimeManager.migrateLegacyStore()
            // Builds before the bounded owner map left one key per token.
            self.sharedDefaults.removeLegacyShieldOwnerKeys()
            // Shield snoozes whose meter is gone, and meters whose snooze is
            // — at launch and at every return to the foreground (the backstop
            // for a snooze left without a meter, or expired at midnight,
            // while the app stays alive). The module lives as long as the
            // app context, so the observer is never removed.
            self.reconcileSnoozes()
            // A revoked authorization is only observable here, at launch and
            // on every return to the foreground (getEnforcementGaps).
            self.observeAuthorization()
            NotificationCenter.default.addObserver(
                forName: UIApplication.didBecomeActiveNotification,
                object: nil,
                queue: nil
            ) { _ in
                DispatchQueue.global(qos: .utility).async {
                    self.reconcileSnoozes()
                    self.observeAuthorization()
                }
            }
        }

        // MARK: - Permissions

        // `options` (`ScreenTimePermissionOptions`) only matter when requesting:
        // the authorization status is the same whichever member asked.
        AsyncFunction("getScreenTimePermissionsAsync") { (_: [String: Any]?, promise: Promise) in
            appContext?.permissions?.getPermissionUsingRequesterClass(
                ScreenTimePermissionRequester.self,
                resolve: promise.legacyResolver,
                reject: promise.legacyRejecter
            )
        }

        AsyncFunction("requestScreenTimePermissionsAsync") { (options: [String: Any]?, promise: Promise) in
            self.permissionRequester.prepareRequest(member: options?["member"] as? String)
            appContext?.permissions?.askForPermission(
                usingRequesterClass: ScreenTimePermissionRequester.self,
                resolve: promise.legacyResolver,
                reject: promise.legacyRejecter
            )
        }

        // MARK: - App Control

        Function("denyAppRemoval") {
            screenTimeManager.denyAppRemoval()
        }

        Function("allowAppRemoval") {
            screenTimeManager.allowAppRemoval()
        }

        Function("denyAppInstallation") {
            screenTimeManager.denyAppInstallation()
        }

        Function("allowAppInstallation") {
            screenTimeManager.allowAppInstallation()
        }

        // MARK: - Blocking

        Function("blockSelection") { (selectionId: String) throws in
            guard SelectionStore.shared.has(selectionId) else {
                throw screenTimeError(.selectionNotFound, "Selection '\(selectionId)' not found")
            }
            // An explicit block supersedes any in-flight temporary unlock —
            // including a grant still being committed (unlockOperations) —
            // and every shield snooze.
            self.unlockOperations.lock()
            defer { self.unlockOperations.unlock() }
            self.stopUnlockMeter()
            BlockStateLock.shared.withLock {
                self.sharedDefaults.clearUnlockState()
                _ = self.sharedDefaults.takeSnoozes()
                // The manual block is one source among the schedules and
                // thresholds in progress; it is persisted so a relock — from
                // this process or from the monitor extension when the budget
                // runs out — restores exactly this selection.
                self.sharedDefaults.setBlockedSelectionId(selectionId)
                self.screenTimeManager.applyActiveSources()
            }
            self.stopSnoozeMeters()
            // Force iOS to invoke ShieldConfigurationExtension for custom shield UI
            screenTimeManager.refreshShields()
        }

        // Lifts every active block — manual, schedule, threshold — and the
        // web-domain filter. Monitoring keeps running: an interval in progress
        // stays in progress (its end is still recorded) and a schedule
        // re-applies its block at its next interval start.
        Function("unblockAll") {
            self.unlockOperations.lock()
            defer { self.unlockOperations.unlock() }
            self.stopUnlockMeter()
            BlockStateLock.shared.withLock {
                self.sharedDefaults.clearUnlockState()
                _ = self.sharedDefaults.takeSnoozes()
                self.sharedDefaults.setBlockedSelectionId(nil)
                self.sharedDefaults.liftActiveIntervals()
                self.screenTimeManager.unblockAll()
                self.sharedDefaults.clearShieldOwners()
            }
            self.stopSnoozeMeters()
        }

        Function("isBlocked") { () -> Bool in
            return screenTimeManager.isBlocked()
        }

        AsyncFunction("temporaryUnlock") { (budgetSeconds: Int, promise: Promise) in
            guard #available(iOS 15.0, *) else {
                promise.reject(.unsupported, "Temporary unlock requires iOS 15.0+")
                return
            }

            // App-side unlock operations (grant, renewal, relock, expiry) run
            // one at a time: they arm or stop the meter through
            // DeviceActivityCenter, which BlockStateLock must not span.
            self.unlockOperations.lock()
            defer { self.unlockOperations.unlock() }

            // Apple's usage thresholds are unreliable below ~30s; a smaller
            // budget couldn't be metered.
            let budget = UsageMeter.budget(for: budgetSeconds)

            // Everything blocked right now — the manual block plus every
            // schedule and threshold in progress — is suspended and metered.
            // Sources already suspended by a grant in progress count too: a
            // renewal replaces that grant (every store is empty then, so the
            // real stores cannot be the judge).
            //
            // The meter must be armed outside BlockStateLock, so the capture
            // and the commit are two critical sections. The commit re-captures
            // and only goes ahead if the shielded tokens are still exactly the
            // ones being metered; otherwise (a `blockSelection` or a schedule
            // start in between) it re-arms the meter for the new union.
            var metered: Set<ShieldToken>? = nil
            for _ in 0..<Self.grantAttempts {
                let tokens = BlockStateLock.shared.withLock {
                    BlockComposition.union(of: self.sharedDefaults.activeBlockSources(honouringSuspension: false))
                }
                guard !tokens.isEmpty else {
                    promise.reject(.noActiveBlock, "Nothing is blocked — call blockSelection or start a schedule first")
                    return
                }

                do {
                    try self.armUnlockMonitoring(budget: budget, tokens: tokens)
                } catch {
                    promise.reject(.monitoringError, "Could not arm usage metering: \(error.localizedDescription)")
                    return
                }

                let grantedAt = Date()
                let committed: Bool = BlockStateLock.shared.withLock {
                    let records = self.sharedDefaults.blockRecords()
                    let current = BlockComposition.union(of: self.sharedDefaults.activeBlockSources(honouringSuspension: false))
                    guard current == tokens else { return false }
                    // Persist what is suspended, then project every store from
                    // the state: the suspended sources' stores come out empty,
                    // and every later projection (here or in the monitor
                    // extension) keeps them so until relock or expiry; a source
                    // that starts after this commit is not suspended.
                    self.sharedDefaults.setUnlockState(
                        budgetSeconds: budget,
                        grantedAt: grantedAt,
                        suspended: SuspendedSources.capturing(records)
                    )
                    self.screenTimeManager.applyActiveSources()
                    return true
                }
                if committed {
                    metered = tokens
                    promise.resolve([
                        "budgetSeconds": budget,
                        "grantedAt": grantedAt.timeIntervalSince1970 * 1000
                    ])
                    break
                }
            }
            if metered == nil {
                // The block kept changing under the grant. The meter armed for
                // the last attempt measures shields that are not suspended, so
                // drop it; any previous grant is left as it was.
                if self.sharedDefaults.getUnlockBudgetSeconds() == 0 {
                    self.stopUnlockMeter()
                }
                promise.reject(.monitoringError, "The block changed while the unlock was being granted; try again")
            }
        }

        Function("isTemporarilyUnlocked") { () -> Bool in
            return self.remainingUnlockSeconds() > 0
        }

        // Remaining budget = granted − consumed (as reported by the monitor's
        // threshold steps). It moves in step-sized decrements, not smoothly —
        // and only while a blocked app is being used. Reading 0 while a budget
        // was granted means it was spent (or the day rolled over): re-apply the
        // shield from here too, as a backstop in case the monitor extension's
        // final threshold never fired.
        Function("getRemainingUnlockSeconds") { () -> Int in
            let remaining = self.remainingUnlockSeconds()
            if remaining <= 0 {
                self.relockIfExpired()
            }
            return remaining
        }

        Function("relock") {
            self.relock()
        }

        // MARK: - Shield interactions

        // Read-and-clear the queue written by the ShieldAction extension —
        // one entry per blocked-app open the user reacted to. Call on app
        // foreground and persist app-side to power "blocked N times" stats.
        Function("drainShieldInteractions") { () -> [[String: Any]] in
            return self.sharedDefaults.drainShieldInteractions()
        }

        Function("blockWebDomains") { (domains: [String]) in
            screenTimeManager.blockWebDomains(domains)
        }

        Function("unblockWebDomains") { (domains: [String]) in
            screenTimeManager.unblockWebDomains(domains)
        }

        Function("unblockAllWebDomains") {
            screenTimeManager.unblockAllWebDomains()
        }

        Function("getBlockedWebDomains") { () -> [String] in
            return screenTimeManager.getBlockedWebDomains()
        }

        // MARK: - Reporting

        // MARK: - Capabilities

        Function("getCapabilities") { () -> [String: Any] in
            return self.capabilities()
        }

        AsyncFunction("getEnforcementGaps") { (since: Double?) -> [[String: Any]] in
            self.observeAuthorization()
            return self.authorizationGaps.withLock { self.loadGapTracker() }
                .gaps(since: since)
                .map(\.dictionary)
        }

        AsyncFunction("openApplication") { (_: String, promise: Promise) in
            promise.reject(.unsupported, "iOS cannot open another app; check getCapabilities().openApplication first")
        }

        Function("getAppGroup") { () -> String? in
            guard let bundleId = Bundle.main.bundleIdentifier else { return nil }
            return "group.\(bundleId)"
        }

        // Serialized report template (JSON from the JS Report.* serializer).
        // The CustomTemplateReport scene in the report extension reads it back.
        Function("setReportTemplate") { (templateJson: String) in
            guard let bundleId = Bundle.main.bundleIdentifier,
                  let defaults = UserDefaults(suiteName: "group.\(bundleId)") else {
                return
            }
            defaults.set(templateJson, forKey: "expo_st_report_template")
            defaults.synchronize()
        }

        Function("clearReportTemplate") {
            guard let bundleId = Bundle.main.bundleIdentifier,
                  let defaults = UserDefaults(suiteName: "group.\(bundleId)") else {
                return
            }
            defaults.removeObject(forKey: "expo_st_report_template")
            defaults.synchronize()
        }

        // MARK: - Debug

        AsyncFunction("debugShieldConfig") { () -> [String: Any] in
            var result: [String: Any] = [:]
            result["appGroup"] = self.sharedDefaults.getAppGroup()
            result["defaultConfig"] = self.sharedDefaults.getDefaultShieldConfig()
            result["blockedSelectionId"] = self.sharedDefaults.getBlockedSelectionId()
            result["activeIntervals"] = self.sharedDefaults.getActiveIntervalNames()
            result["shieldStores"] = self.sharedDefaults.shieldStoreNames()
            result["suspended"] = self.sharedDefaults.getSuspendedSources()?.dictionary

            // Read shield extension debug log if it exists
            if let appGroup = self.sharedDefaults.getAppGroup(),
               let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
                let logURL = containerURL.appendingPathComponent("shield_config_debug.log")
                if let logContent = try? String(contentsOf: logURL, encoding: .utf8) {
                    // Get last 500 chars
                    let suffix = String(logContent.suffix(500))
                    result["extensionLog"] = suffix
                } else {
                    result["extensionLog"] = "No log file found at \(logURL.path)"
                }
            }

            return result
        }

        // MARK: - Selection Store

        AsyncFunction("listSelections") { () -> [String] in
            return SelectionStore.shared.list()
        }

        AsyncFunction("hasSelection") { (selectionId: String) -> Bool in
            return SelectionStore.shared.has(selectionId)
        }

        AsyncFunction("getSelectionMetadata") { (selectionId: String) -> [String: Int]? in
            return SelectionStore.shared.getMetadata(selectionId)
        }

        AsyncFunction("deleteSelection") { (selectionId: String) in
            SelectionStore.shared.delete(selectionId)
        }

        AsyncFunction("getTokens") { (selectionId: String) -> [[String: String]]? in
            return SelectionStore.shared.getTokenHandles(selectionId)
        }

        AsyncFunction("removeToken") { (selectionId: String, handle: String) in
            SelectionStore.shared.removeToken(selectionId, handle: handle)
        }

        // MARK: - Selection Set Operations

        AsyncFunction("mergeSelections") { (targetId: String, sourceIds: [String]) in
            SelectionStore.shared.merge(targetId, sourceIds)
        }

        AsyncFunction("keepCommonSelections") { (targetId: String, sourceIds: [String]) in
            SelectionStore.shared.keepCommon(targetId, sourceIds)
        }

        AsyncFunction("subtractSelection") { (targetId: String, fromId: String, subtractId: String) in
            SelectionStore.shared.subtract(targetId, fromId: fromId, subtractId: subtractId)
        }

        // MARK: - Selection Export/Import

        AsyncFunction("exportSelection") { (selectionId: String) -> String? in
            return SelectionStore.shared.exportSelection(selectionId)
        }

        AsyncFunction("importSelection") { (selectionId: String, data: String) -> Bool in
            return SelectionStore.shared.importSelection(selectionId, base64Data: data)
        }

        // MARK: - Monitoring

        AsyncFunction("startMonitoring") { (config: [String: Any], promise: Promise) in
            guard #available(iOS 15.0, *) else {
                promise.reject(.unsupported, "DeviceActivity monitoring requires iOS 15.0+")
                return
            }

            guard let activityName = config["activityName"] as? String else {
                promise.reject(.invalidConfig, "activityName is required")
                return
            }

            // The physical DeviceActivityName is `<activityName><separator><generation>`
            // (ActivityRegistration); a name containing the separator could
            // not be told apart from a generation suffix, and `expo_st_*` is
            // the module's own namespace (the unlock meter).
            guard ActivityRegistration.isValidLogicalName(activityName) else {
                promise.reject(.invalidConfig, Self.invalidActivityNameMessage)
                return
            }

            guard let scheduleDict = config["schedule"] as? [String: Any] else {
                promise.reject(.invalidConfig, "schedule is required")
                return
            }

            do {
                let deviceActivityEvents = try self.buildEvents(from: config)

                // One DeviceActivity per logical name. `weekdays` no longer
                // registers one activity per weekday: the monitor extension
                // ignores occurrences on unselected days (DailySchedule), so
                // events and shield configs use this name and the activity
                // costs one slot.
                let weekdays = Array(DailySchedule.weekdays(from: config["weekdays"] as? [Any])).sorted()
                let schedule = try self.buildSchedule(from: scheduleDict)

                try self.register(
                    activityName: activityName,
                    config: config,
                    weekdays: weekdays,
                    scheduleDict: scheduleDict,
                    schedule: schedule,
                    events: deviceActivityEvents
                )

                promise.resolve(nil)
            } catch let error as DeviceActivityCenter.MonitoringError {
                switch error {
                case .excessiveActivities:
                    promise.reject(.excessiveActivities, "Maximum of 20 activities can be monitored simultaneously")
                case .intervalTooLong:
                    promise.reject(.intervalTooLong, "Schedule interval cannot exceed 1 week")
                case .intervalTooShort:
                    promise.reject(.intervalTooShort, "Schedule interval must be at least 15 minutes")
                case .invalidDateComponents:
                    promise.reject(.invalidDateComponents, "Invalid date components in schedule")
                case .unauthorized:
                    promise.reject(.unauthorized, "Family Controls authorization required")
                @unknown default:
                    promise.reject(.monitoringError, "Failed to start monitoring: \(error.localizedDescription)")
                }
            } catch let error as RollbackError {
                promise.reject(
                    .monitoringRollbackFailed,
                    "Failed to start monitoring '\(activityName)' (\(error.registration.localizedDescription)) and its previous "
                        + "registration could not be restored (\(error.rollback.localizedDescription)): the activity is no longer monitored"
                )
            } catch let error as Exception {
                // Already coded (e.g. INVALID_CONFIG from buildSchedule).
                promise.reject(error)
            } catch {
                promise.reject(.monitoringError, "Failed to start monitoring: \(error.localizedDescription)")
            }
        }

        AsyncFunction("stopMonitoring") { (activityName: String, promise: Promise) in
            guard #available(iOS 15.0, *) else {
                promise.reject(.unsupported, "DeviceActivity monitoring requires iOS 15.0+")
                return
            }

            // The same rule as `startMonitoring`: the module's own activities
            // (the unlock meter) cannot be stopped through the public API.
            guard ActivityRegistration.isValidLogicalName(activityName) else {
                promise.reject(.invalidConfig, Self.invalidActivityNameMessage)
                return
            }

            let center = DeviceActivityCenter()
            let config = self.sharedDefaults.getMonitoringConfig(activityName: activityName)
            let legacyVariants = ActivityRegistration.legacyWeekdayVariants(in: config, of: activityName)
            let physicals = Self.registeredPhysicalNames(of: activityName, legacyVariants: legacyVariants, in: center)

            // App Group first, under the lock: the `intervalDidEnd` the stop
            // triggers arrives within milliseconds, takes the lock after this
            // section, finds no registration, and records nothing — the end
            // is recorded here, once.
            BlockStateLock.shared.withLock {
                self.endMonitoring(activityName)
                self.removeLegacyVariantState(legacyVariants, of: activityName)
            }
            Self.stop(physicals, on: center)

            promise.resolve(nil)
        }

        AsyncFunction("stopAllMonitoring") { (promise: Promise) in
            guard #available(iOS 15.0, *) else {
                promise.reject(.unsupported, "DeviceActivity monitoring requires iOS 15.0+")
                return
            }

            // Stop the app's activities only. The temporary-unlock meter is
            // internal: stopping it froze consumption, so a granted unlock
            // could never re-shield and the block stayed off until midnight.
            // The manual block and the web filter are separate sources and
            // stay in force.
            let center = DeviceActivityCenter()
            let physicals = center.activities.filter { !ActivityRegistration.isInternalName($0.rawValue) }

            BlockStateLock.shared.withLock {
                let activityNames = Set(self.sharedDefaults.getAllActivityNames())
                    .union(self.sharedDefaults.getActiveIntervalNames())
                for activityName in activityNames.sorted() {
                    let config = self.sharedDefaults.getMonitoringConfig(activityName: activityName)
                    if let parent = config?[ActivityRegistration.legacyParentKey] as? String,
                       ActivityRegistration.isLegacyVariantState(config, variant: activityName, of: parent) {
                        // A legacy weekday variant's config: no interval, no store of its own.
                        self.removeLegacyVariantState([activityName], of: parent)
                    } else {
                        self.endMonitoring(activityName)
                    }
                }
            }
            Self.stop(physicals, on: center)

            promise.resolve(nil)
        }

        AsyncFunction("getMonitoredActivities") { () -> [String] in
            guard #available(iOS 15.0, *) else {
                return []
            }

            // Physical names carry a generation suffix, and builds before
            // block composition registered `<name>_weekday_<n>` variants:
            // report every registration under its logical name.
            let center = DeviceActivityCenter()
            let lookup = { self.sharedDefaults.getMonitoringConfig(activityName: $0) }
            var names = Set<String>()
            for activity in center.activities where !ActivityRegistration.isInternalName(activity.rawValue) {
                names.insert(ActivityRegistration.logicalName(ofRegistered: activity.rawValue, config: lookup))
            }
            return names.sorted()
        }

        // MARK: - Shield Configuration

        Function("setShieldConfig") { (config: [String: Any]) in
            self.sharedDefaults.saveDefaultShieldConfig(config)
            self.screenTimeManager.refreshShields()
        }

        Function("setShieldConfigForActivity") { (activityName: String, config: [String: Any]) in
            self.sharedDefaults.saveShieldConfig(activityName: activityName, config: config)
            self.screenTimeManager.refreshShields()
        }

        Function("clearShieldConfig") {
            self.sharedDefaults.clearShieldConfigs()
            self.screenTimeManager.refreshShields()
        }

        // MARK: - Shield Actions

        Function("setShieldActions") { (config: [String: Any]) in
            self.sharedDefaults.saveDefaultShieldActions(config)
        }

        Function("setShieldActionsForActivity") { (activityName: String, config: [String: Any]) in
            self.sharedDefaults.saveShieldActions(activityName: activityName, config: config)
        }

        Function("clearShieldActions") {
            self.sharedDefaults.clearShieldActions()
        }

        // MARK: - Event History

        AsyncFunction("getMonitorEvents") { (activityName: String?, limit: Int?) -> [[String: Any]] in
            let effectiveLimit = limit ?? 100

            if let activityName = activityName {
                return self.sharedDefaults.getEvents(activityName: activityName, limit: effectiveLimit)
            } else {
                return self.sharedDefaults.getAllEvents(limit: effectiveLimit)
            }
        }

        AsyncFunction("clearMonitorEvents") { (activityName: String?, promise: Promise) in
            self.sharedDefaults.clearEvents(activityName: activityName)
            promise.resolve(nil)
        }

        // MARK: - Real-time Event Listening

        OnStartObserving("onMonitorEvent") {
            guard !self.isListening else { return }
            self.isListening = true
            let subscribedAt = Date().timeIntervalSince1970 * 1000

            DispatchQueue.main.async {
                // Reset the cursor before any reader can run: only events
                // recorded from now on are emitted live (history stays
                // available through getMonitorEvents).
                self.eventCursor = MonitorEventCursor(startingAt: subscribedAt)
                self.setupDarwinNotificationObserver()

                // Poll every 2 seconds as fallback
                self.pollTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
                    self?.pollForNewEvents()
                }
            }
        }

        OnStopObserving("onMonitorEvent") {
            self.isListening = false
            self.removeDarwinNotificationObserver()

            DispatchQueue.main.async {
                self.pollTimer?.invalidate()
                self.pollTimer = nil
            }
        }

        // MARK: - View Registration

        View(ExpoScreenTimeActivityPickerView.self) {
            Prop("selectionId") { (view: ExpoScreenTimeActivityPickerView, selectionId: String?) in
                view.setSelectionId(selectionId)
            }

            Prop("headerText") { (view: ExpoScreenTimeActivityPickerView, headerText: String?) in
                view.setHeaderText(headerText)
            }

            Prop("footerText") { (view: ExpoScreenTimeActivityPickerView, footerText: String?) in
                view.setFooterText(footerText)
            }

            Events("onSelectionChange", "onOpen", "onClose")
        }

        View(ExpoScreenTimeReportView.self) {
            Prop("filterSegment") { (view: ExpoScreenTimeReportView, segment: String) in
                view.setFilterSegment(segment)
            }

            Prop("filterDate") { (view: ExpoScreenTimeReportView, timestamp: Double) in
                view.setFilterDate(timestamp)
            }

            Prop("templateHash") { (view: ExpoScreenTimeReportView, hash: String?) in
                view.setTemplateHash(hash)
            }
        }

        View(ExpoScreenTimeTokenLabelView.self) {
            Prop("tokenType") { (view: ExpoScreenTimeTokenLabelView, tokenType: String?) in
                view.setTokenType(tokenType)
            }

            Prop("displayMode") { (view: ExpoScreenTimeTokenLabelView, displayMode: String?) in
                view.setDisplayMode(displayMode)
            }

            Prop("selectionId") { (view: ExpoScreenTimeTokenLabelView, selectionId: String?) in
                view.setSelectionId(selectionId)
            }

            Prop("handle") { (view: ExpoScreenTimeTokenLabelView, handle: String?) in
                view.setHandle(handle)
            }
        }
    }

    // MARK: - Private Helpers

    @available(iOS 15.0, *)
    private func buildSchedule(from dict: [String: Any]) throws -> DeviceActivitySchedule {
        guard let intervalStartDict = dict["intervalStart"] as? [String: Any],
              let intervalEndDict = dict["intervalEnd"] as? [String: Any] else {
            throw screenTimeError(.invalidConfig, "schedule.intervalStart and schedule.intervalEnd are required")
        }

        let intervalStart = buildDateComponents(from: intervalStartDict)
        let intervalEnd = buildDateComponents(from: intervalEndDict)
        let repeats = dict["repeats"] as? Bool ?? true

        var warningTime: DateComponents?
        if let warningSeconds = Self.intValue(dict["warningTime"]), warningSeconds > 0 {
            var components = DateComponents()
            components.second = warningSeconds
            warningTime = components
        }

        return DeviceActivitySchedule(
            intervalStart: intervalStart,
            intervalEnd: intervalEnd,
            repeats: repeats,
            warningTime: warningTime
        )
    }

    /// An integer from a JS value. JS numbers reach Swift as `Double` inside
    /// `[String: Any]`, and `Double as? Int` is always nil, so every numeric
    /// read from a config dictionary goes through here.
    static func intValue(_ value: Any?) -> Int? {
        return DailySchedule.intValue(value)
    }

    private func buildDateComponents(from dict: [String: Any]) -> DateComponents {
        var components = DateComponents()

        if let hour = Self.intValue(dict["hour"]) {
            components.hour = hour
        }
        if let minute = Self.intValue(dict["minute"]) {
            components.minute = minute
        }
        if let second = Self.intValue(dict["second"]) {
            components.second = second
        }
        if let weekday = Self.intValue(dict["weekday"]) {
            components.weekday = weekday
        }

        return components
    }

    @available(iOS 15.0, *)
    private func buildEvent(from dict: [String: Any]) throws -> (DeviceActivityEvent.Name, DeviceActivityEvent)? {
        guard let eventName = dict["eventName"] as? String else {
            return nil
        }

        guard let thresholdSeconds = Self.intValue(dict["threshold"]) else {
            return nil
        }

        var thresholdComponents = DateComponents()
        let hours = thresholdSeconds / 3600
        let minutes = (thresholdSeconds % 3600) / 60
        let seconds = thresholdSeconds % 60

        if hours > 0 {
            thresholdComponents.hour = hours
        }
        if minutes > 0 {
            thresholdComponents.minute = minutes
        }
        if seconds > 0 {
            thresholdComponents.second = seconds
        }

        var applications: Set<ApplicationToken> = []
        var categories: Set<ActivityCategoryToken> = []
        var webDomains: Set<WebDomainToken> = []

        if let selectionId = dict["selectionId"] as? String,
           let selection = SelectionStore.shared.get(selectionId) {
            applications = selection.applicationTokens
            categories = selection.categoryTokens
            webDomains = selection.webDomainTokens
        }

        let includesPastActivity = dict["includesPastActivity"] as? Bool ?? true

        let event: DeviceActivityEvent
        if #available(iOS 17.4, *) {
            event = DeviceActivityEvent(
                applications: applications,
                categories: categories,
                webDomains: webDomains,
                threshold: thresholdComponents,
                includesPastActivity: includesPastActivity
            )
        } else {
            event = DeviceActivityEvent(
                applications: applications,
                categories: categories,
                webDomains: webDomains,
                threshold: thresholdComponents
            )
        }

        return (DeviceActivityEvent.Name(rawValue: eventName), event)
    }

    // MARK: - Monitoring Helpers

    /// What the system has registered for `activityName`: its current (or
    /// previous) generation, a legacy `<name>` registration, and the legacy
    /// weekday variants its config lists.
    @available(iOS 15.0, *)
    static func registeredPhysicalNames(
        of activityName: String,
        legacyVariants: [String],
        in center: DeviceActivityCenter
    ) -> [DeviceActivityName] {
        return center.activities.filter {
            ActivityRegistration.parse($0.rawValue).logical == activityName || legacyVariants.contains($0.rawValue)
        }
    }

    /// The registration `startMonitoring` replaces, as it was, for the rollback.
    private struct PreviousRegistration {
        var config: [String: Any]?
        var selectionId: String?
        var interval: ActiveInterval?
        var thresholds: [String]
        /// Legacy `<name>_weekday_<n>` variants: their config and selection.
        var legacyVariants: [(name: String, config: [String: Any]?, selectionId: String?)]
        /// What the system has registered for the activity.
        var physicals: [DeviceActivityName]
    }

    /// A registration failed after the previous one had been released (the
    /// 20-activity limit), and putting the previous one back failed too: the
    /// activity is no longer monitored.
    struct RollbackError: Error {
        let registration: Error
        let rollback: Error
    }

    static let invalidActivityNameMessage =
        "activityName must not be empty, start with '\(ActivityRegistration.reservedPrefix)' "
        + "or contain '\(ActivityRegistration.generationSeparator)'"

    /// Register `activityName` — or re-register it: a schedule update, or a
    /// restart right after a stop.
    ///
    /// Order matters, because the system calls back within milliseconds: the
    /// new registration's `intervalDidStart` when now is inside the
    /// interval, and the previous registration's `intervalDidEnd` when it is
    /// stopped. So the App Group is put in its final state FIRST, in one
    /// critical section of `BlockStateLock` — the config with a fresh
    /// generation, the selection, the interval marker and the store per
    /// `ActivityRegistration.intervalPlan`, the thresholds reset — then the
    /// lock is released, the new physical name is registered, and the
    /// previous registrations are stopped. The start callback takes the lock
    /// after this section, finds the marker and records nothing; the end
    /// callback carries a generation that is no longer current and is
    /// ignored. The shields never drop. (The lock is not held across the
    /// system calls: see BlockStateLock.)
    ///
    /// When the registration throws, the previous registration keeps running
    /// and its state is put back (`rollBack`) — except at the 20-activity
    /// limit, where the previous registration has to be released before the
    /// new one can be tried: a failure after that re-registers the previous
    /// schedule under a fresh generation, and if THAT fails the activity is
    /// stopped and a `RollbackError` is thrown.
    @available(iOS 15.0, *)
    private func register(
        activityName: String,
        config: [String: Any],
        weekdays: [Int],
        scheduleDict: [String: Any],
        schedule: DeviceActivitySchedule,
        events: [DeviceActivityEvent.Name: DeviceActivityEvent]
    ) throws {
        let center = DeviceActivityCenter()
        let defaults = sharedDefaults
        let lock = BlockStateLock.shared
        let now = Date()
        let decision = DailySchedule.syncDecision(scheduleDict: scheduleDict, weekdays: weekdays, now: now, calendar: Calendar.current)

        // What the system has registered for this activity — an XPC read,
        // outside the lock.
        let legacyVariantNames = ActivityRegistration.legacyWeekdayVariants(
            in: defaults.getMonitoringConfig(activityName: activityName), of: activityName
        )
        let previousPhysicals = Self.registeredPhysicalNames(of: activityName, legacyVariants: legacyVariantNames, in: center)

        // 1. Snapshot the previous registration, then the App Group in its
        //    final state — one critical section.
        let (previous, generation, plan) = lock.withLock { () -> (PreviousRegistration, Int, ActivityRegistration.IntervalPlan) in
            let previous = PreviousRegistration(
                config: defaults.getMonitoringConfig(activityName: activityName),
                selectionId: defaults.getSelectionId(activityName: activityName),
                interval: defaults.getActiveInterval(activityName: activityName),
                thresholds: defaults.getThresholdSelectionIds(activityName: activityName),
                legacyVariants: legacyVariantNames.map {
                    ($0, defaults.getMonitoringConfig(activityName: $0), defaults.getSelectionId(activityName: $0))
                },
                physicals: previousPhysicals
            )

            let generation = defaults.nextRegistrationGeneration()
            var storedConfig = config
            storedConfig["weekdays"] = weekdays
            storedConfig[ActivityRegistration.generationKey] = generation
            defaults.saveMonitoringConfig(activityName: activityName, config: storedConfig)
            if let selectionId = config["selectionId"] as? String {
                defaults.saveSelectionId(activityName: activityName, selectionId: selectionId)
            } else {
                defaults.removeSelectionId(activityName: activityName)
            }
            removeLegacyVariantState(legacyVariantNames, of: activityName)

            // `intervalDidStart` is asynchronous (it fires at the first device
            // use after the boundary), so a schedule registered inside its own
            // occurrence would not block until then: when now is inside an
            // occurrence on a selected weekday, the block is applied here and
            // the start recorded once. Outside, or when the new schedule is
            // not a daily one, nothing of the previous registration's
            // interval may linger (a daily activity replaced by a multi-day
            // one kept shielding from the stale marker); the monitor applies
            // the new one. A re-registration re-arms the thresholds, so they
            // are reset before the registration — a threshold callback can
            // fire within milliseconds of it.
            let plan = ActivityRegistration.intervalPlan(for: decision, previous: previous.interval, now: now, restoring: false)
            apply(plan, to: activityName)
            return (previous, generation, plan)
        }

        // 2. Register the new physical name; 3. stop the previous ones.
        let physical = DeviceActivityName(rawValue: ActivityRegistration.physicalName(logical: activityName, generation: generation))
        var previousStopped = false
        do {
            do {
                try center.startMonitoring(physical, during: schedule, events: events)
            } catch DeviceActivityCenter.MonitoringError.excessiveActivities where !previous.physicals.isEmpty {
                // At the limit the two registrations cannot overlap.
                Self.stop(previous.physicals, on: center)
                previousStopped = true
                try center.startMonitoring(physical, during: schedule, events: events)
            }
            if !previousStopped {
                Self.stop(previous.physicals, on: center)
            }
        } catch {
            try rollBack(activityName, to: previous, previousStopped: previousStopped, after: error, on: center)
            throw error
        }

        // 4. The events, now that the registration holds.
        record(plan, for: activityName)
    }

    /// Apply a plan's state: the marker, the thresholds, this activity's
    /// store (projected from the state). Call under the lock.
    private func apply(_ plan: ActivityRegistration.IntervalPlan, to activityName: String) {
        switch plan.marker {
        case .keep:
            break
        case .set(let startedAt):
            sharedDefaults.setActiveInterval(activityName: activityName, startedAt: startedAt)
        case .clear:
            sharedDefaults.clearActiveInterval(activityName: activityName)
        }
        if !plan.keepsThresholds {
            sharedDefaults.clearThresholdSelections(activityName: activityName)
        }
        screenTimeManager.applyStore(forActivity: activityName)
    }

    /// Record the events a plan implies, once its registration holds.
    private func record(_ plan: ActivityRegistration.IntervalPlan, for activityName: String) {
        if plan.recordsEnd {
            sharedDefaults.recordEvent(activityName: activityName, eventType: "intervalDidEnd")
        }
        if plan.recordsStart {
            sharedDefaults.recordEvent(activityName: activityName, eventType: "intervalDidStart")
        }
        if plan.recordsStart || plan.recordsEnd {
            notifyEventListeners()
        }
    }

    /// The new registration failed. Put `previous` back, or forget the
    /// activity when there was nothing before.
    ///
    /// Previous registration still running (`previousStopped` false): its
    /// state goes back as it was, re-planned against the clock — a boundary
    /// callback it fired during the window in which the config carried the
    /// new generation was ignored, and for a daily schedule the plan
    /// recovers the missed start or end. For a schedule this process cannot
    /// evaluate, a boundary crossed inside that window (one XPC call) is
    /// caught up at the next one.
    ///
    /// Previous registration released (the 20-activity limit): it is
    /// re-registered from its stored config under a FRESH generation
    /// (`ActivityRegistration.rollbackConfig`), its interval restored, so the
    /// stale callbacks of the released registration stay stale. If that
    /// registration fails too, the activity is stopped — an interval that
    /// was in progress before the call ends here (one started by the plan
    /// was never recorded, so it ends silently) — and a `RollbackError` is
    /// thrown.
    @available(iOS 15.0, *)
    private func rollBack(
        _ activityName: String,
        to previous: PreviousRegistration,
        previousStopped: Bool,
        after error: Error,
        on center: DeviceActivityCenter
    ) throws {
        let defaults = sharedDefaults
        let lock = BlockStateLock.shared
        let now = Date()

        guard let previousConfig = previous.config else {
            // Nothing was registered before: forget what step 1 wrote, with
            // no event — no start was recorded.
            lock.withLock { forget(activityName, recordingEnd: false) }
            return
        }

        func restoreInterval() -> ActivityRegistration.IntervalPlan {
            if let interval = previous.interval {
                defaults.setActiveInterval(activityName: activityName, startedAt: interval.startedAt, lifted: interval.lifted)
                defaults.setThresholdSelectionIds(activityName: activityName, selectionIds: previous.thresholds)
            } else {
                defaults.clearActiveInterval(activityName: activityName)
            }
            let plan = ActivityRegistration.intervalPlan(
                for: Self.syncDecision(of: previousConfig, now: now), previous: previous.interval, now: now, restoring: true
            )
            apply(plan, to: activityName)
            return plan
        }

        func restoreSelection() {
            if let selectionId = previous.selectionId {
                defaults.saveSelectionId(activityName: activityName, selectionId: selectionId)
            } else {
                defaults.removeSelectionId(activityName: activityName)
            }
        }

        if !previousStopped {
            let plan = lock.withLock { () -> ActivityRegistration.IntervalPlan in
                defaults.saveMonitoringConfig(activityName: activityName, config: previousConfig)
                restoreSelection()
                for variant in previous.legacyVariants {
                    if let variantConfig = variant.config {
                        defaults.saveMonitoringConfig(activityName: variant.name, config: variantConfig)
                    }
                    if let variantSelectionId = variant.selectionId {
                        defaults.saveSelectionId(activityName: variant.name, selectionId: variantSelectionId)
                    }
                }
                return restoreInterval()
            }
            record(plan, for: activityName)
            return
        }

        // Re-register the previous schedule under a fresh generation.
        let restoredSchedule: DeviceActivitySchedule
        let restoredEvents: [DeviceActivityEvent.Name: DeviceActivityEvent]
        do {
            guard let scheduleDict = previousConfig["schedule"] as? [String: Any] else {
                throw screenTimeError(.monitoringError, "the stored schedule is missing")
            }
            restoredSchedule = try buildSchedule(from: scheduleDict)
            restoredEvents = try buildEvents(from: previousConfig)
        } catch let rollbackError {
            lock.withLock { forget(activityName, recordingEnd: previous.interval != nil) }
            throw RollbackError(registration: error, rollback: rollbackError)
        }

        let (restoredPhysical, plan) = lock.withLock { () -> (DeviceActivityName, ActivityRegistration.IntervalPlan) in
            let generation = defaults.nextRegistrationGeneration()
            defaults.saveMonitoringConfig(
                activityName: activityName,
                config: ActivityRegistration.rollbackConfig(previous: previousConfig, generation: generation)
            )
            restoreSelection()
            let plan = restoreInterval()
            return (DeviceActivityName(rawValue: ActivityRegistration.physicalName(logical: activityName, generation: generation)), plan)
        }
        do {
            try center.startMonitoring(restoredPhysical, during: restoredSchedule, events: restoredEvents)
        } catch let rollbackError {
            lock.withLock { forget(activityName, recordingEnd: previous.interval != nil) }
            throw RollbackError(registration: error, rollback: rollbackError)
        }
        record(plan, for: activityName)
    }

    /// What a stored config says about now (`DailySchedule.syncDecision`).
    private static func syncDecision(of config: [String: Any], now: Date) -> DailySchedule.SyncDecision {
        guard let scheduleDict = config["schedule"] as? [String: Any] else { return .leaveToMonitor }
        return DailySchedule.syncDecision(scheduleDict: scheduleDict, weekdays: config["weekdays"] as? [Any], now: now, calendar: Calendar.current)
    }

    /// The usage events of a config (`events`), built for the system.
    @available(iOS 15.0, *)
    private func buildEvents(from config: [String: Any]) throws -> [DeviceActivityEvent.Name: DeviceActivityEvent] {
        var events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
        for eventConfig in config["events"] as? [[String: Any]] ?? [] {
            if let (eventName, event) = try buildEvent(from: eventConfig) {
                events[eventName] = event
            }
        }
        return events
    }

    /// Monitoring stopped for `activityName`: record its `intervalDidEnd` if an
    /// occurrence was in progress, forget the interval and its thresholds,
    /// drop its configuration, empty its store, forget its shield owners.
    /// Call under the lock, BEFORE the system registration is stopped, so
    /// the callback that stop triggers finds no registration and records
    /// nothing.
    private func endMonitoring(_ activityName: String) {
        forget(activityName, recordingEnd: true)
    }

    /// Drop every trace of `activityName`'s registration. Call under the lock.
    private func forget(_ activityName: String, recordingEnd: Bool) {
        if recordingEnd, sharedDefaults.getActiveInterval(activityName: activityName) != nil {
            sharedDefaults.recordEvent(activityName: activityName, eventType: "intervalDidEnd")
            notifyEventListeners()
        }
        sharedDefaults.clearActiveInterval(activityName: activityName)
        sharedDefaults.removeMonitoringConfig(activityName: activityName)
        sharedDefaults.removeSelectionId(activityName: activityName)
        screenTimeManager.clearStore(named: BlockStore.activity(activityName))
        sharedDefaults.pruneShieldOwners(ofActivity: activityName)
    }

    /// Builds before block composition stored a config and a selection per
    /// `<name>_weekday_<n>` variant. Variants never had a store or an
    /// interval of their own. Only state that is STILL a legacy variant's is
    /// removed: a name on the legacy list may have been registered since as
    /// an activity of its own, with a generation. Call under the lock.
    private func removeLegacyVariantState(_ variants: [String], of parent: String) {
        for variant in variants
        where ActivityRegistration.isLegacyVariantState(sharedDefaults.getMonitoringConfig(activityName: variant), variant: variant, of: parent) {
            sharedDefaults.removeMonitoringConfig(activityName: variant)
            sharedDefaults.removeSelectionId(activityName: variant)
        }
    }

    /// An event was written by this process: deliver it like the monitor's
    /// Darwin notification would.
    private func notifyEventListeners() {
        DispatchQueue.main.async {
            self.pollForNewEvents()
        }
    }

    // MARK: - Temporary Unlock Helpers

    /// Reserved DeviceActivity name for the unlock's usage metering — the
    /// monitor extension routes it away from the regular monitoring handlers.
    static let unlockActivityName = "expo_st_temporary_unlock"

    /// Stop exactly `activities`. `DeviceActivityCenter.stopMonitoring([])`
    /// stops *every* activity (an empty list means "all"), the unlock meter
    /// included, so an empty selection must be a no-op.
    @available(iOS 15.0, *)
    static func stop(_ activities: [DeviceActivityName], on center: DeviceActivityCenter) {
        guard !activities.isEmpty else { return }
        center.stopMonitoring(activities)
    }

    /// Event names carry their threshold: `expo_st_unlock_step_<seconds>`.
    static let unlockStepPrefix = "expo_st_unlock_step_"

    /// Register the usage-threshold ladder for `budget` seconds over every
    /// token the active sources shield (`UsageMeter`, the ladder the shield
    /// snoozes use too). Each step lets the monitor extension persist how
    /// much of the budget is spent; the final step (== budget) re-blocks.
    @available(iOS 15.0, *)
    private func armUnlockMonitoring(budget: Int, tokens: Set<ShieldToken>) throws {
        let center = DeviceActivityCenter()
        // Replace any previous unlock metering (a fresh grant restarts the count).
        center.stopMonitoring([DeviceActivityName(Self.unlockActivityName)])

        var events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
        for seconds in UsageMeter.thresholds(budget: budget) {
            events[DeviceActivityEvent.Name("\(Self.unlockStepPrefix)\(seconds)")] = DeviceActivityEvent(
                applications: tokens.applicationTokens,
                categories: tokens.categoryTokens,
                webDomains: tokens.webDomainTokens,
                threshold: DateComponents(
                    hour: seconds / 3600,
                    minute: (seconds % 3600) / 60,
                    second: seconds % 60
                )
            )
        }

        // The interval must start NOW: thresholds measure usage accumulated
        // within the interval, so an all-day interval would count this
        // morning's usage and could be crossed before metering even begins
        // (the system never re-fires a threshold already exceeded). Ending at
        // 23:59:59 gives the monitor a day-boundary callback to expire any
        // unspent budget — earned time doesn't carry across midnight.
        let startComponents = Calendar.current.dateComponents([.hour, .minute, .second], from: Date())
        let schedule = DeviceActivitySchedule(
            intervalStart: startComponents,
            intervalEnd: DateComponents(hour: 23, minute: 59, second: 59),
            repeats: false
        )

        try center.startMonitoring(
            DeviceActivityName(Self.unlockActivityName),
            during: schedule,
            events: events
        )
    }

    /// Stop the metering schedule — a system call, made outside the lock.
    /// The unlock state is cleared by the caller, under the lock, together
    /// with whatever it does to the shields.
    private func stopUnlockMeter() {
        if #available(iOS 15.0, *) {
            DeviceActivityCenter().stopMonitoring([DeviceActivityName(Self.unlockActivityName)])
        }
    }

    /// End any active unlock — and every shield snooze: "put the shields
    /// back" means all of them — and restore exactly the sources still
    /// active: the manual block, and every interval (plus its thresholds)
    /// still in progress.
    private func relock() {
        unlockOperations.lock()
        defer { unlockOperations.unlock() }
        stopUnlockMeter()
        BlockStateLock.shared.withLock {
            sharedDefaults.clearUnlockState()
            _ = sharedDefaults.takeSnoozes()
            screenTimeManager.applyActiveSources()
        }
        stopSnoozeMeters()
    }

    // MARK: - Shield snooze helpers
    //
    // A shield snooze (`ShieldSnooze`) is granted by the shield action
    // extension, which also arms its meter; the monitor extension spends it.
    // The app only ends snoozes (`blockSelection`, `unblockAll`, `relock`)
    // and tidies up: the records go first, under the lock, so that the
    // `intervalDidEnd` a stop triggers finds no snooze; then the meters are
    // stopped, outside the lock. A meter is one of the 20 DeviceActivity
    // slots, so none may be left running without its snooze.

    /// Stop every snooze meter the system still runs except `live` —
    /// those of snoozes that were spent, replaced, expired or ended. Outside
    /// the lock (a system call).
    private func stopSnoozeMeters(keeping live: Set<String> = []) {
        guard #available(iOS 15.0, *) else { return }
        let center = DeviceActivityCenter()
        let stale = center.activities.filter { ShieldSnooze.isActivityName($0.rawValue) && !live.contains($0.rawValue) }
        Self.stop(stale, on: center)
    }

    /// At launch and on foreground: a snooze whose meter the system does
    /// not run cannot be spent (the extension was killed between recording
    /// it and arming the meter, or the meter ended) — drop it, so its target
    /// is shielded again; a snooze from a previous day is dropped too (its
    /// projection may have outlived midnight); and a meter without its
    /// snooze — spent ones, which the monitor extension cannot stop — is
    /// released, whether or not any snooze is recorded. Snoozes granted in
    /// the last few seconds are left alone: the extension may be arming the
    /// meter right now.
    private func reconcileSnoozes() {
        guard #available(iOS 16.0, *),
              ScreenTimePermissionRequester.isApproved(AuthorizationCenter.shared.authorizationStatus) else {
            return
        }
        let registered = Set(DeviceActivityCenter().activities.map(\.rawValue))
        guard !sharedDefaults.getSnoozes().isEmpty || registered.contains(where: ShieldSnooze.isActivityName) else {
            return
        }
        let live: Set<String> = BlockStateLock.shared.withLock {
            let now = Date()
            let current = sharedDefaults.currentSnoozes()
            let kept = current.filter {
                registered.contains($0.activityName) || now.timeIntervalSince1970 - $0.grantedAt / 1000 < 10
            }
            if kept != sharedDefaults.getSnoozes() {
                sharedDefaults.setSnoozes(kept)
                screenTimeManager.applyActiveSources()
            }
            return Set(kept.map(\.activityName))
        }
        stopSnoozeMeters(keeping: live)
    }

    /// The backstop behind `getRemainingUnlockSeconds`: relock only if the
    /// grant is STILL spent when checked under the lock, so a renewal that
    /// landed after the caller read 0 is not cancelled.
    private func relockIfExpired() {
        unlockOperations.lock()
        defer { unlockOperations.unlock() }
        let expired: Bool = BlockStateLock.shared.withLock {
            guard sharedDefaults.getUnlockBudgetSeconds() > 0, remainingUnlockSeconds() <= 0 else { return false }
            sharedDefaults.clearUnlockState()
            screenTimeManager.applyActiveSources()
            return true
        }
        if expired {
            stopUnlockMeter()
        }
    }

    /// Serializes this process's unlock operations (grant, renewal, relock,
    /// expiry). In-process only, so — unlike BlockStateLock — it may be held
    /// across the DeviceActivityCenter calls that arm or stop the meter: the
    /// monitor extension never takes it.
    private let unlockOperations = NSLock()

    /// How many times a grant re-captures when the block changes under it.
    static let grantAttempts = 3

    /// Budget minus consumption, floored at 0. A grant from a previous
    /// calendar day is stale (the daily reset may not have fired if the
    /// device slept through it).
    private func remainingUnlockSeconds() -> Int {
        let budget = sharedDefaults.getUnlockBudgetSeconds()
        guard budget > 0 else { return 0 }

        if let grantedAt = sharedDefaults.getUnlockGrantedAt(),
           !Calendar.current.isDate(grantedAt, inSameDayAs: Date()) {
            return 0
        }

        return max(0, budget - sharedDefaults.getUnlockConsumedSeconds())
    }

    // MARK: - Darwin Notification Helpers

    private static let darwinNotificationName = "com.expo.screentime.monitorEvent" as CFString

    private func setupDarwinNotificationObserver() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let observer = Unmanaged.passUnretained(self).toOpaque()

        CFNotificationCenterAddObserver(
            center,
            observer,
            { (_, observer, _, _, _) in
                guard let observer = observer else { return }
                let module = Unmanaged<ExpoScreenTimeModule>.fromOpaque(observer).takeUnretainedValue()
                DispatchQueue.main.async {
                    module.pollForNewEvents()
                }
            },
            ExpoScreenTimeModule.darwinNotificationName,
            nil,
            .deliverImmediately
        )
    }

    private func removeDarwinNotificationObserver() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let observer = Unmanaged.passUnretained(self).toOpaque()

        CFNotificationCenterRemoveObserver(
            center,
            observer,
            CFNotificationName(ExpoScreenTimeModule.darwinNotificationName),
            nil
        )
    }

    private func pollForNewEvents() {
        guard isListening else { return }

        // Several events can land between two reads (e.g. one activity ends
        // as another starts): emit all of them, oldest first, exactly once.
        for event in eventCursor.take(from: sharedDefaults.getAllEvents(limit: 200)) {
            sendEvent("onMonitorEvent", ["event": event])
        }
    }

    // MARK: - Capabilities & enforcement gaps

    /// `UserDefaults.standard` key of the persisted `AuthorizationGapTracker`.
    private static let gapTrackerKey = "expo_st_enforcement_gaps"
    /// Serializes the tracker's read-fold-write (launch, foreground, JS calls).
    private let authorizationGaps = NSLock()

    /// Call with `authorizationGaps` held.
    private func loadGapTracker() -> AuthorizationGapTracker {
        guard let data = UserDefaults.standard.data(forKey: Self.gapTrackerKey),
              let tracker = try? JSONDecoder().decode(AuthorizationGapTracker.self, from: data) else {
            return AuthorizationGapTracker()
        }
        return tracker
    }

    /// Fold the current authorization status into the persisted tracker.
    private func observeAuthorization() {
        guard #available(iOS 16.0, *) else { return }
        let approved = ScreenTimePermissionRequester.isApproved(AuthorizationCenter.shared.authorizationStatus)
        authorizationGaps.withLock {
            var tracker = loadGapTracker()
            let before = tracker
            tracker.observe(approved: approved, at: Date().timeIntervalSince1970 * 1000)
            if tracker != before, let data = try? JSONEncoder().encode(tracker) {
                UserDefaults.standard.set(data, forKey: Self.gapTrackerKey)
            }
        }
    }

    /// What this build, on this device, with the authorization last granted,
    /// can do (`ScreenTimeCapabilities`). Synchronous and cheap.
    private func capabilities() -> [String: Any] {
        let limits: [String: Any] = [
            // iOS caps DeviceActivity at 20 and the unlock meter holds one.
            "maxMonitoredActivities": 19,
            "minIntervalSeconds": 15 * 60,
            "maxIntervalSeconds": 7 * 24 * 60 * 60,
        ]
        guard #available(iOS 16.0, *) else {
            return [
                "blocking": "none",
                "selectableKinds": ["applications": false, "categories": false, "webDomains": false],
                "webDomainBlocking": false,
                "appRemovalProtection": "none",
                "appInstallationBlocking": "none",
                "usageReports": false,
                "shieldOpenApp": "notification",
                "openApplication": false,
                "limits": limits,
            ]
        }
        // `.individual` access can be revoked in Settings, which lifts the
        // restrictions; `.child` access is approved and held by a parent.
        let protection = ScreenTimePermissionRequester.authorizedMember == "child" ? "enforced" : "friction"
        return [
            "blocking": "enforced",
            "selectableKinds": ["applications": true, "categories": true, "webDomains": true],
            "webDomainBlocking": true,
            "appRemovalProtection": protection,
            "appInstallationBlocking": protection,
            // The config plugin can leave the report extension out.
            "usageReports": Self.embedsExtension("DeviceActivityReportExtension"),
            "shieldOpenApp": "notification",
            "openApplication": false,
            "limits": limits,
        ]
    }

    /// Whether the app bundle embeds the app extension target `name`.
    private static func embedsExtension(_ name: String) -> Bool {
        guard let plugIns = Bundle.main.builtInPlugInsURL else { return false }
        return FileManager.default.fileExists(atPath: plugIns.appendingPathComponent("\(name).appex").path)
    }
}
