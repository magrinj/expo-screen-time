import ManagedSettings
import ManagedSettingsUI
import UIKit
import SwiftUI

/// Shield Configuration Extension
/// Customizes the appearance of the shield (blocking) screen
class ShieldConfigurationExtension: ShieldConfigurationDataSource {

    private let defaults = SharedDefaults.shared

    // MARK: - Configuration Methods

    /// Configuration for shielded applications
    override func configuration(shielding application: Application) -> ShieldConfiguration {
        #if DEBUG
        // Log to a file since we can't use print() in extensions
        logDebug("configuration(shielding application) called")
        #endif
        return buildConfiguration()
    }

    /// Configuration for shielded application categories
    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration {
        return buildConfiguration()
    }

    /// Configuration for shielded web domains
    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        return buildConfiguration()
    }

    /// Configuration for shielded web domain categories
    override func configuration(shielding webDomain: WebDomain, in category: ActivityCategory) -> ShieldConfiguration {
        return buildConfiguration()
    }

    // MARK: - Private Helpers

    #if DEBUG
    private func logDebug(_ message: String) {
        // Write to a shared log file that the main app can read
        let logMessage = "[\(Date())] \(message)\n"
        if let data = logMessage.data(using: .utf8),
           let bundleId = Bundle.main.bundleIdentifier {
            let baseBundleId = bundleId.replacingOccurrences(of: ".ShieldConfigurationExtension", with: "")
            if let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.\(baseBundleId)") {
                let logURL = containerURL.appendingPathComponent("shield_config_debug.log")
                if FileManager.default.fileExists(atPath: logURL.path) {
                    if let fileHandle = try? FileHandle(forWritingTo: logURL) {
                        fileHandle.seekToEndOfFile()
                        fileHandle.write(data)
                        fileHandle.closeFile()
                    }
                } else {
                    try? data.write(to: logURL)
                }
            }
        }
    }
    #endif

    /// Build the shield configuration from stored settings
    private func buildConfiguration() -> ShieldConfiguration {
        // Try to get activity-specific config first, then fall back to default
        var configDict: [String: Any]?

        if let activeActivity = defaults.getActiveActivity() {
            #if DEBUG
            logDebug("Active activity: \(activeActivity)")
            #endif
            configDict = defaults.getShieldConfig(activityName: activeActivity)
        }

        if configDict == nil {
            configDict = defaults.getDefaultShieldConfig()
            #if DEBUG
            logDebug("Using default config: \(configDict?.description ?? "nil")")
            #endif
        }

        guard let config = configDict else {
            #if DEBUG
            logDebug("No config found, returning default ShieldConfiguration")
            #endif
            // Return default configuration
            return ShieldConfiguration()
        }

        #if DEBUG
        logDebug("Building config with: \(config)")
        #endif

        // Build the configuration
        var shieldConfig = ShieldConfiguration()

        // Background
        if let bgColorHex = config["backgroundColor"] as? String,
           let bgColor = UIColor(hex: bgColorHex) {
            shieldConfig = ShieldConfiguration(
                backgroundColor: bgColor,
                title: buildTitle(from: config),
                subtitle: buildSubtitle(from: config),
                primaryButtonLabel: buildPrimaryButton(from: config),
                primaryButtonBackgroundColor: buildPrimaryButtonBg(from: config),
                secondaryButtonLabel: buildSecondaryButton(from: config)
            )
        } else {
            shieldConfig = ShieldConfiguration(
                title: buildTitle(from: config),
                subtitle: buildSubtitle(from: config),
                primaryButtonLabel: buildPrimaryButton(from: config),
                primaryButtonBackgroundColor: buildPrimaryButtonBg(from: config),
                secondaryButtonLabel: buildSecondaryButton(from: config)
            )
        }

        // Background blur style
        if let blurStyleString = config["backgroundBlurStyle"] as? String,
           let blurStyle = UIBlurEffect.Style(rawString: blurStyleString) {
            shieldConfig = ShieldConfiguration(
                backgroundBlurStyle: blurStyle,
                backgroundColor: shieldConfig.backgroundColor,
                title: shieldConfig.title,
                subtitle: shieldConfig.subtitle,
                primaryButtonLabel: shieldConfig.primaryButtonLabel,
                primaryButtonBackgroundColor: shieldConfig.primaryButtonBackgroundColor,
                secondaryButtonLabel: shieldConfig.secondaryButtonLabel
            )
        }

        // Icon
        if let iconName = config["icon"] as? String {
            shieldConfig = ShieldConfiguration(
                backgroundBlurStyle: shieldConfig.backgroundBlurStyle,
                backgroundColor: shieldConfig.backgroundColor,
                icon: UIImage(systemName: iconName),
                title: shieldConfig.title,
                subtitle: shieldConfig.subtitle,
                primaryButtonLabel: shieldConfig.primaryButtonLabel,
                primaryButtonBackgroundColor: shieldConfig.primaryButtonBackgroundColor,
                secondaryButtonLabel: shieldConfig.secondaryButtonLabel
            )
        }

        return shieldConfig
    }

    private func buildTitle(from config: [String: Any]) -> ShieldConfiguration.Label? {
        // New nested format: { text: "...", color: "#..." }
        if let titleDict = config["title"] as? [String: Any],
           let text = titleDict["text"] as? String {
            if let colorHex = titleDict["color"] as? String, let color = UIColor(hex: colorHex) {
                return ShieldConfiguration.Label(text: text, color: color)
            }
            return ShieldConfiguration.Label(text: text, color: .label)
        }
        // Legacy flat format: title as string
        if let text = config["title"] as? String {
            if let colorHex = config["titleColor"] as? String, let color = UIColor(hex: colorHex) {
                return ShieldConfiguration.Label(text: text, color: color)
            }
            return ShieldConfiguration.Label(text: text, color: .label)
        }
        return nil
    }

    private func buildSubtitle(from config: [String: Any]) -> ShieldConfiguration.Label? {
        if let subtitleDict = config["subtitle"] as? [String: Any],
           let text = subtitleDict["text"] as? String {
            if let colorHex = subtitleDict["color"] as? String, let color = UIColor(hex: colorHex) {
                return ShieldConfiguration.Label(text: text, color: color)
            }
            return ShieldConfiguration.Label(text: text, color: .secondaryLabel)
        }
        if let text = config["subtitle"] as? String {
            if let colorHex = config["subtitleColor"] as? String, let color = UIColor(hex: colorHex) {
                return ShieldConfiguration.Label(text: text, color: color)
            }
            return ShieldConfiguration.Label(text: text, color: .secondaryLabel)
        }
        return nil
    }

    private func buildPrimaryButton(from config: [String: Any]) -> ShieldConfiguration.Label? {
        if let btnDict = config["primaryButton"] as? [String: Any],
           let text = btnDict["text"] as? String {
            if let colorHex = btnDict["color"] as? String, let color = UIColor(hex: colorHex) {
                return ShieldConfiguration.Label(text: text, color: color)
            }
            return ShieldConfiguration.Label(text: text, color: .white)
        }
        // Legacy
        if let text = config["primaryButtonLabel"] as? String {
            if let colorHex = config["primaryButtonColor"] as? String, let color = UIColor(hex: colorHex) {
                return ShieldConfiguration.Label(text: text, color: color)
            }
            return ShieldConfiguration.Label(text: text, color: .white)
        }
        return nil
    }

    private func buildPrimaryButtonBg(from config: [String: Any]) -> UIColor? {
        // New nested format
        if let btnDict = config["primaryButton"] as? [String: Any],
           let colorHex = btnDict["backgroundColor"] as? String {
            return UIColor(hex: colorHex)
        }
        // Legacy
        if let colorHex = config["primaryButtonBackgroundColor"] as? String {
            return UIColor(hex: colorHex)
        }
        return nil
    }

    private func buildSecondaryButton(from config: [String: Any]) -> ShieldConfiguration.Label? {
        if let btnDict = config["secondaryButton"] as? [String: Any],
           let text = btnDict["text"] as? String {
            if let colorHex = btnDict["color"] as? String, let color = UIColor(hex: colorHex) {
                return ShieldConfiguration.Label(text: text, color: color)
            }
            return ShieldConfiguration.Label(text: text, color: .systemBlue)
        }
        // Legacy
        if let text = config["secondaryButtonLabel"] as? String {
            if let colorHex = config["secondaryButtonColor"] as? String, let color = UIColor(hex: colorHex) {
                return ShieldConfiguration.Label(text: text, color: color)
            }
            return ShieldConfiguration.Label(text: text, color: .systemBlue)
        }
        return nil
    }
}

// MARK: - UIColor Extension

extension UIColor {
    convenience init?(hex: String) {
        var hexSanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")

        var rgb: UInt64 = 0

        guard Scanner(string: hexSanitized).scanHexInt64(&rgb) else {
            return nil
        }

        let length = hexSanitized.count

        if length == 6 {
            let r = CGFloat((rgb & 0xFF0000) >> 16) / 255.0
            let g = CGFloat((rgb & 0x00FF00) >> 8) / 255.0
            let b = CGFloat(rgb & 0x0000FF) / 255.0
            self.init(red: r, green: g, blue: b, alpha: 1.0)
        } else if length == 8 {
            let r = CGFloat((rgb & 0xFF000000) >> 24) / 255.0
            let g = CGFloat((rgb & 0x00FF0000) >> 16) / 255.0
            let b = CGFloat((rgb & 0x0000FF00) >> 8) / 255.0
            let a = CGFloat(rgb & 0x000000FF) / 255.0
            self.init(red: r, green: g, blue: b, alpha: a)
        } else {
            return nil
        }
    }
}

// MARK: - UIBlurEffect.Style Extension

extension UIBlurEffect.Style {
    init?(rawString: String) {
        switch rawString {
        case "extraLight": self = .extraLight
        case "light": self = .light
        case "dark": self = .dark
        case "regular": self = .regular
        case "prominent": self = .prominent
        case "systemUltraThinMaterial": self = .systemUltraThinMaterial
        case "systemThinMaterial": self = .systemThinMaterial
        case "systemMaterial": self = .systemMaterial
        case "systemThickMaterial": self = .systemThickMaterial
        case "systemChromeMaterial": self = .systemChromeMaterial
        case "systemUltraThinMaterialLight": self = .systemUltraThinMaterialLight
        case "systemThinMaterialLight": self = .systemThinMaterialLight
        case "systemMaterialLight": self = .systemMaterialLight
        case "systemThickMaterialLight": self = .systemThickMaterialLight
        case "systemChromeMaterialLight": self = .systemChromeMaterialLight
        case "systemUltraThinMaterialDark": self = .systemUltraThinMaterialDark
        case "systemThinMaterialDark": self = .systemThinMaterialDark
        case "systemMaterialDark": self = .systemMaterialDark
        case "systemThickMaterialDark": self = .systemThickMaterialDark
        case "systemChromeMaterialDark": self = .systemChromeMaterialDark
        default: return nil
        }
    }
}
