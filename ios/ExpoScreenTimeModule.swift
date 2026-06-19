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

    // Event listening state
    private var isListening = false
    private var lastProcessedTimestamp: Double = 0
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
            screenTimeManager.blockSelection(selection)
            // Force iOS to invoke ShieldConfigurationExtension for custom shield UI
            screenTimeManager.refreshShields()
        }

        Function("unblockAll") {
            screenTimeManager.unblockAll()
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

        Function("setReportStyle") { (style: [String: Any]) in
            guard let bundleId = Bundle.main.bundleIdentifier,
                  let defaults = UserDefaults(suiteName: "group.\(bundleId)") else {
                return
            }

            if let accentColor = style["accentColor"] as? String {
                defaults.set(accentColor, forKey: "expo_st_accentColor")
            }
            if let backgroundColor = style["backgroundColor"] as? String {
                defaults.set(backgroundColor, forKey: "expo_st_backgroundColor")
            }
            if let textColor = style["textColor"] as? String {
                defaults.set(textColor, forKey: "expo_st_textColor")
            }
            if let showPickups = style["showPickups"] as? Bool {
                defaults.set(showPickups, forKey: "expo_st_showPickups")
            }
            if let showTopApps = style["showTopApps"] as? Bool {
                defaults.set(showTopApps, forKey: "expo_st_showTopApps")
            }
            if let showChart = style["showChart"] as? Bool {
                defaults.set(showChart, forKey: "expo_st_showChart")
            }
            if let chartStyle = style["chartStyle"] as? String {
                defaults.set(chartStyle, forKey: "expo_st_chartStyle")
            }

            defaults.synchronize()
        }

        Function("getAppGroup") { () -> String? in
            guard let bundleId = Bundle.main.bundleIdentifier else { return nil }
            return "group.\(bundleId)"
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
                if let weekdays = config["weekdays"] as? [Int], !weekdays.isEmpty {
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

        Function("startEventListening") {
            guard !self.isListening else { return }
            self.isListening = true
            self.lastProcessedTimestamp = Date().timeIntervalSince1970 * 1000

            self.setupDarwinNotificationObserver()

            // Poll every 2 seconds as fallback
            DispatchQueue.main.async {
                self.pollTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
                    self?.pollForNewEvents()
                }
            }
        }

        Function("stopEventListening") {
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
            Prop("context") { (view: ExpoScreenTimeReportView, context: String) in
                view.setContext(context)
            }

            Prop("filterSegment") { (view: ExpoScreenTimeReportView, segment: String) in
                view.setFilterSegment(segment)
            }

            Prop("filterDate") { (view: ExpoScreenTimeReportView, timestamp: Double) in
                view.setFilterDate(timestamp)
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
        if let warningSeconds = dict["warningTime"] as? Int, warningSeconds > 0 {
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

    private func buildDateComponents(from dict: [String: Any]) -> DateComponents {
        var components = DateComponents()

        if let hour = dict["hour"] as? Int {
            components.hour = hour
        }
        if let minute = dict["minute"] as? Int {
            components.minute = minute
        }
        if let second = dict["second"] as? Int {
            components.second = second
        }
        if let weekday = dict["weekday"] as? Int {
            components.weekday = weekday
        }

        return components
    }

    @available(iOS 15.0, *)
    private func buildEvent(from dict: [String: Any]) throws -> (DeviceActivityEvent.Name, DeviceActivityEvent)? {
        guard let eventName = dict["eventName"] as? String else {
            return nil
        }

        guard let thresholdSeconds = dict["threshold"] as? Int else {
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

        let allEvents = sharedDefaults.getAllEvents(limit: 50)

        for event in allEvents {
            guard let timestamp = event["timestamp"] as? Double else { continue }

            if timestamp > lastProcessedTimestamp {
                lastProcessedTimestamp = timestamp
                sendEvent("onMonitorEvent", ["event": event])
            }
        }
    }
}
