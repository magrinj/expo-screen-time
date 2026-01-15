import SwiftUI

/// Configuration for report styling, reads from App Group UserDefaults
/// Values are set from JavaScript via ExpoScreenTime.setReportStyle()
struct ReportConfiguration {
    static let shared = ReportConfiguration()

    private var defaults: UserDefaults?

    // Keys for UserDefaults (prefixed to avoid collisions)
    private enum Keys {
        static let accentColor = "expo_st_accentColor"
        static let backgroundColor = "expo_st_backgroundColor"
        static let textColor = "expo_st_textColor"
        static let showPickups = "expo_st_showPickups"
        static let showTopApps = "expo_st_showTopApps"
        static let showChart = "expo_st_showChart"
        static let chartStyle = "expo_st_chartStyle"
    }

    init() {
        // Try to get App Group from Info.plist or use a common pattern
        if let appGroup = Bundle.main.object(forInfoDictionaryKey: "AppGroup") as? String {
            defaults = UserDefaults(suiteName: appGroup)
        } else if let bundleId = Bundle.main.bundleIdentifier {
            // Try common app group patterns
            let possibleGroups = [
                "group.\(bundleId)",
                "group.\(bundleId.replacingOccurrences(of: ".device-activity-report", with: ""))"
            ]
            for group in possibleGroups {
                if let userDefaults = UserDefaults(suiteName: group) {
                    defaults = userDefaults
                    break
                }
            }
        }
    }

    // MARK: - Colors

    var accentColor: Color {
        if let hex = defaults?.string(forKey: Keys.accentColor) {
            return Color(hex: hex) ?? .blue
        }
        return .blue
    }

    var backgroundColor: Color {
        if let hex = defaults?.string(forKey: Keys.backgroundColor) {
            return Color(hex: hex) ?? Color(.systemBackground)
        }
        return Color(.systemBackground)
    }

    var textColor: Color {
        if let hex = defaults?.string(forKey: Keys.textColor) {
            return Color(hex: hex) ?? Color(.label)
        }
        return Color(.label)
    }

    var secondaryTextColor: Color {
        return textColor.opacity(0.6)
    }

    // MARK: - Display Options

    var showPickups: Bool {
        return defaults?.bool(forKey: Keys.showPickups) ?? true
    }

    var showTopApps: Bool {
        return defaults?.bool(forKey: Keys.showTopApps) ?? true
    }

    var showChart: Bool {
        return defaults?.bool(forKey: Keys.showChart) ?? true
    }

    var chartStyle: String {
        return defaults?.string(forKey: Keys.chartStyle) ?? "bar"
    }
}

// MARK: - Color Extension for Hex Support

extension Color {
    init?(hex: String) {
        var hexSanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")

        var rgb: UInt64 = 0

        guard Scanner(string: hexSanitized).scanHexInt64(&rgb) else {
            return nil
        }

        let length = hexSanitized.count

        if length == 6 {
            let r = Double((rgb & 0xFF0000) >> 16) / 255.0
            let g = Double((rgb & 0x00FF00) >> 8) / 255.0
            let b = Double(rgb & 0x0000FF) / 255.0
            self.init(red: r, green: g, blue: b)
        } else if length == 8 {
            let r = Double((rgb & 0xFF000000) >> 24) / 255.0
            let g = Double((rgb & 0x00FF0000) >> 16) / 255.0
            let b = Double((rgb & 0x0000FF00) >> 8) / 255.0
            let a = Double(rgb & 0x000000FF) / 255.0
            self.init(red: r, green: g, blue: b, opacity: a)
        } else {
            return nil
        }
    }
}
