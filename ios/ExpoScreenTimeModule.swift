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
                ScreenTimePermissionRequester()
            ])
        }

        // MARK: - Permissions

        AsyncFunction("getScreenTimePermissionsAsync") { (promise: Promise) in
            appContext?.permissions?.getPermissionUsingRequesterClass(
                ScreenTimePermissionRequester.self,
                resolve: promise.legacyResolver,
                reject: promise.legacyRejecter
            )
        }

        AsyncFunction("requestScreenTimePermissionsAsync") { (promise: Promise) in
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
            guard let selection = SelectionStore.shared.get(selectionId) else {
                throw NSError(domain: "ExpoScreenTime", code: 2, userInfo: [
                    NSLocalizedDescriptionKey: "Selection '\(selectionId)' not found"
                ])
            }
            // An explicit block supersedes any in-flight temporary unlock.
            self.cancelTemporaryUnlock()
            // Persisted so a relock — from this process or from the monitor
            // extension when the budget runs out — restores exactly this
            // selection.
            self.sharedDefaults.setBlockedSelectionId(selectionId)
            screenTimeManager.blockSelection(selection)
            // Force iOS to invoke ShieldConfigurationExtension for custom shield UI
            screenTimeManager.refreshShields()
        }

        Function("unblockAll") {
            self.cancelTemporaryUnlock()
            self.sharedDefaults.setBlockedSelectionId(nil)
            screenTimeManager.unblockAll()
        }

        Function("isBlocked") { () -> Bool in
            return screenTimeManager.isBlocked()
        }

        AsyncFunction("temporaryUnlock") { (budgetSeconds: Int, promise: Promise) in
            guard #available(iOS 15.0, *) else {
                promise.reject("UNSUPPORTED", "Temporary unlock requires iOS 15.0+")
                return
            }

            guard let selectionId = self.sharedDefaults.getBlockedSelectionId(),
                  let selection = SelectionStore.shared.get(selectionId),
                  self.screenTimeManager.isBlocked() else {
                promise.reject("NO_ACTIVE_BLOCK", "Nothing is blocked — call blockSelection first")
                return
            }

            // Apple's usage thresholds are unreliable below ~30s; a smaller
            // budget couldn't be metered.
            let budget = max(Self.unlockStepSeconds, budgetSeconds)
            let grantedAt = Date()

            do {
                try self.armUnlockMonitoring(budget: budget, selection: selection)
            } catch {
                promise.reject("MONITORING_ERROR", "Could not arm usage metering: \(error.localizedDescription)")
                return
            }

            self.sharedDefaults.setUnlockState(budgetSeconds: budget, grantedAt: grantedAt)
            self.screenTimeManager.suspendShields()

            promise.resolve([
                "budgetSeconds": budget,
                "grantedAt": grantedAt.timeIntervalSince1970 * 1000
            ])
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
            let hadBudget = self.sharedDefaults.getUnlockBudgetSeconds() > 0
            let remaining = self.remainingUnlockSeconds()
            if remaining <= 0 && hadBudget {
                self.relock()
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
            result["activeActivity"] = self.sharedDefaults.getActiveActivity()

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
                promise.reject("UNSUPPORTED", "DeviceActivity monitoring requires iOS 15.0+")
                return
            }

            guard let activityName = config["activityName"] as? String else {
                promise.reject("INVALID_CONFIG", "activityName is required")
                return
            }

            guard let scheduleDict = config["schedule"] as? [String: Any] else {
                promise.reject("INVALID_CONFIG", "schedule is required")
                return
            }

            do {
                // Build events if provided
                var deviceActivityEvents: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
                if let eventsArray = config["events"] as? [[String: Any]] {
                    for eventConfig in eventsArray {
                        if let (eventName, event) = try self.buildEvent(from: eventConfig) {
                            deviceActivityEvents[eventName] = event
                        }
                    }
                }

                let center = DeviceActivityCenter()

                // Check if weekdays array is provided
                if let weekdays = (config["weekdays"] as? [Any])?.compactMap(Self.intValue), !weekdays.isEmpty {
                    // Create one activity per weekday
                    var weekdayActivityNames: [String] = []

                    for weekday in weekdays {
                        let weekdayActivityName = "\(activityName)_weekday_\(weekday)"
                        weekdayActivityNames.append(weekdayActivityName)

                        // Build schedule with this specific weekday
                        var weekdayScheduleDict = scheduleDict
                        if var intervalStart = weekdayScheduleDict["intervalStart"] as? [String: Any] {
                            intervalStart["weekday"] = weekday
                            weekdayScheduleDict["intervalStart"] = intervalStart
                        }
                        if var intervalEnd = weekdayScheduleDict["intervalEnd"] as? [String: Any] {
                            intervalEnd["weekday"] = weekday
                            weekdayScheduleDict["intervalEnd"] = intervalEnd
                        }

                        let schedule = try self.buildSchedule(from: weekdayScheduleDict)

                        // Store configuration for extension
                        var weekdayConfig = config
                        weekdayConfig["_parentActivity"] = activityName
                        self.sharedDefaults.saveMonitoringConfig(activityName: weekdayActivityName, config: weekdayConfig)

                        // Store selectionId if provided
                        if let selectionId = config["selectionId"] as? String {
                            self.sharedDefaults.saveSelectionId(activityName: weekdayActivityName, selectionId: selectionId)
                        }

                        let activity = DeviceActivityName(rawValue: weekdayActivityName)
                        try center.startMonitoring(activity, during: schedule, events: deviceActivityEvents)
                    }

                    // Store parent activity with list of child activities
                    var parentConfig = config
                    parentConfig["_weekdayActivities"] = weekdayActivityNames
                    self.sharedDefaults.saveMonitoringConfig(activityName: activityName, config: parentConfig)

                } else {
                    // Single activity (no weekdays array)
                    let schedule = try self.buildSchedule(from: scheduleDict)

                    // Store configuration for extension
                    self.sharedDefaults.saveMonitoringConfig(activityName: activityName, config: config)

                    // Store selectionId if provided
                    if let selectionId = config["selectionId"] as? String {
                        self.sharedDefaults.saveSelectionId(activityName: activityName, selectionId: selectionId)
                    }

                    let activity = DeviceActivityName(rawValue: activityName)
                    try center.startMonitoring(activity, during: schedule, events: deviceActivityEvents)
                }

                promise.resolve(nil)
            } catch let error as DeviceActivityCenter.MonitoringError {
                switch error {
                case .excessiveActivities:
                    promise.reject("EXCESSIVE_ACTIVITIES", "Maximum of 20 activities can be monitored simultaneously")
                case .intervalTooLong:
                    promise.reject("INTERVAL_TOO_LONG", "Schedule interval cannot exceed 1 week")
                case .intervalTooShort:
                    promise.reject("INTERVAL_TOO_SHORT", "Schedule interval must be at least 15 minutes")
                case .invalidDateComponents:
                    promise.reject("INVALID_DATE_COMPONENTS", "Invalid date components in schedule")
                case .unauthorized:
                    promise.reject("UNAUTHORIZED", "Family Controls authorization required")
                @unknown default:
                    promise.reject("MONITORING_ERROR", "Failed to start monitoring: \(error.localizedDescription)")
                }
            } catch {
                promise.reject("ERROR", "Failed to start monitoring: \(error.localizedDescription)")
            }
        }

        AsyncFunction("stopMonitoring") { (activityName: String, promise: Promise) in
            guard #available(iOS 15.0, *) else {
                promise.reject("UNSUPPORTED", "DeviceActivity monitoring requires iOS 15.0+")
                return
            }

            let center = DeviceActivityCenter()

            // Check if this is a parent activity with weekday variants
            if let config = self.sharedDefaults.getMonitoringConfig(activityName: activityName),
               let weekdayActivities = config["_weekdayActivities"] as? [String] {
                // Stop all weekday variant activities
                let activities = weekdayActivities.map { DeviceActivityName(rawValue: $0) }
                center.stopMonitoring(activities)

                // Clean up all weekday configs
                for weekdayActivityName in weekdayActivities {
                    self.sharedDefaults.removeMonitoringConfig(activityName: weekdayActivityName)
                    self.sharedDefaults.removeSelectionId(activityName: weekdayActivityName)
                }

                // Clean up parent config
                self.sharedDefaults.removeMonitoringConfig(activityName: activityName)
            } else {
                // Single activity
                let activity = DeviceActivityName(rawValue: activityName)
                center.stopMonitoring([activity])

                self.sharedDefaults.removeMonitoringConfig(activityName: activityName)
                self.sharedDefaults.removeSelectionId(activityName: activityName)
            }

            promise.resolve(nil)
        }

        AsyncFunction("stopAllMonitoring") { (promise: Promise) in
            guard #available(iOS 15.0, *) else {
                promise.reject("UNSUPPORTED", "DeviceActivity monitoring requires iOS 15.0+")
                return
            }

            let center = DeviceActivityCenter()
            center.stopMonitoring()

            for activityName in self.sharedDefaults.getAllActivityNames() {
                self.sharedDefaults.removeMonitoringConfig(activityName: activityName)
                self.sharedDefaults.removeSelectionId(activityName: activityName)
            }

            promise.resolve(nil)
        }

        AsyncFunction("getMonitoredActivities") { () -> [String] in
            guard #available(iOS 15.0, *) else {
                return []
            }

            let center = DeviceActivityCenter()
            let rawActivities = center.activities.map { $0.rawValue }

            // Consolidate weekday variants into parent names
            var parentActivities = Set<String>()

            for activityName in rawActivities {
                // Check if this is a weekday variant (contains _weekday_)
                if activityName.contains("_weekday_") {
                    // Extract parent name
                    if let range = activityName.range(of: "_weekday_") {
                        let parentName = String(activityName[..<range.lowerBound])
                        parentActivities.insert(parentName)
                    }
                } else {
                    parentActivities.insert(activityName)
                }
            }

            return Array(parentActivities).sorted()
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
            throw NSError(domain: "ExpoScreenTime", code: 1, userInfo: [NSLocalizedDescriptionKey: "intervalStart and intervalEnd are required"])
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

    // MARK: - Temporary Unlock Helpers

    /// Reserved DeviceActivity name for the unlock's usage metering — the
    /// monitor extension routes it away from the regular monitoring handlers.
    static let unlockActivityName = "expo_st_temporary_unlock"
    /// Event names carry their threshold: `expo_st_unlock_step_<seconds>`.
    static let unlockStepPrefix = "expo_st_unlock_step_"
    /// Metering grain. Below ~30s Apple's usage thresholds fire late or not
    /// at all, so this is the finest step worth registering.
    static let unlockStepSeconds = 30
    /// Ceiling on registered events — DeviceActivity degrades with large
    /// event sets, so the step auto-coarsens for big budgets.
    static let maxUnlockSteps = 60

    /// Register the usage-threshold ladder for `budget` seconds over the
    /// blocked selection. Each step lets the monitor extension persist how
    /// much of the budget is spent; the final step (== budget) re-blocks.
    @available(iOS 15.0, *)
    private func armUnlockMonitoring(budget: Int, selection: FamilyActivitySelection) throws {
        let center = DeviceActivityCenter()
        // Replace any previous unlock metering (a fresh grant restarts the count).
        center.stopMonitoring([DeviceActivityName(Self.unlockActivityName)])

        let step = max(Self.unlockStepSeconds, Int(ceil(Double(budget) / Double(Self.maxUnlockSteps))))
        var thresholds: [Int] = Array(stride(from: step, to: budget, by: step))
        thresholds.append(budget)

        var events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
        for seconds in thresholds {
            events[DeviceActivityEvent.Name("\(Self.unlockStepPrefix)\(seconds)")] = DeviceActivityEvent(
                applications: selection.applicationTokens,
                categories: selection.categoryTokens,
                webDomains: selection.webDomainTokens,
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

    /// Stop the metering schedule and drop all unlock state. Does NOT touch
    /// the shields — callers decide whether to re-apply or clear them.
    private func cancelTemporaryUnlock() {
        if #available(iOS 15.0, *) {
            DeviceActivityCenter().stopMonitoring([DeviceActivityName(Self.unlockActivityName)])
        }
        sharedDefaults.clearUnlockState()
    }

    /// End any active unlock and restore the shield from the persisted
    /// blocked selection.
    private func relock() {
        cancelTemporaryUnlock()
        guard let selectionId = sharedDefaults.getBlockedSelectionId(),
              let selection = SelectionStore.shared.get(selectionId) else {
            return
        }
        screenTimeManager.blockSelection(selection)
    }

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
}
