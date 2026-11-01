import ManagedSettings
import ManagedSettingsUI
import UIKit
import SwiftUI

/// Shield Configuration Extension
/// Customizes the appearance of the shield (blocking) screen
class ShieldConfigurationExtension: ShieldConfigurationDataSource {

    private let defaults = SharedDefaults.shared
    private let lock = BlockStateLock.shared

    // MARK: - Configuration Methods

    /// Configuration for shielded applications
    override func configuration(shielding application: Application) -> ShieldConfiguration {
        #if DEBUG
        // Log to a file since we can't use print() in extensions
        logDebug("configuration(shielding application) called")
        #endif
        return buildConfiguration(
            primary: application.token.map(ShieldToken.application),
            category: nil,
            context: .application
        )
    }

    /// Configuration for shielded application categories
    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration {
        return buildConfiguration(
            primary: application.token.map(ShieldToken.application),
            category: category.token.map(ShieldToken.category),
            context: .application
        )
    }

    /// Configuration for shielded web domains
    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        return buildConfiguration(
            primary: webDomain.token.map(ShieldToken.webDomain),
            category: nil,
            context: .webDomain
        )
    }

    /// Configuration for shielded web domain categories
    override func configuration(shielding webDomain: WebDomain, in category: ActivityCategory) -> ShieldConfiguration {
        return buildConfiguration(
            primary: webDomain.token.map(ShieldToken.webDomain),
            category: category.token.map(ShieldToken.category),
            context: .webDomain
        )
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

    /// Build the shield configuration from stored settings. `primary` is the
    /// app or web domain shielded, `category` the category it was shielded
    /// through, if any (the tokens a shield may have matched: an app shielded
    /// through its category is matched by either).
    private func buildConfiguration(
        primary: ShieldToken?,
        category: ShieldToken?,
        context: ShieldOwnerMap.Context
    ) -> ShieldConfiguration {
        // The config of the source that shields this target — a threshold,
        // else the latest schedule covering it, else the manual block — then
        // the default. Several sources can be active at once; a single
        // "active activity" pointer used to pick whichever started last.
        var configDict: [String: Any]?

        let targets = Set([primary, category].compactMap { $0 })
        // Resolve and hand off in one critical section, so the owner the
        // action extension reads is the one resolved against the same state.
        let owningActivity: String? = lock.withLock {
            // Sources a temporary unlock suspended are not active (they shield
            // nothing right now), so they never own a shield.
            let owner = BlockComposition.owner(ofAnyOf: targets, in: defaults.activeBlockSources())
            // Hand the owner to the action extension, which sees only one of
            // these tokens and could otherwise resolve a different source:
            // under the primary token always, under the category only when
            // the owner shields through it (never an app's own owner under a
            // key every app of the category shares).
            if let primary = primary {
                let handoff = BlockComposition.handoffTargets(of: owner, primary: primary, category: category)
                let keyed = handoff.compactMap { token -> (key: String, context: ShieldOwnerMap.Context)? in
                    guard let key = token.persistentKey else { return nil }
                    return (key, token == primary ? context : .category)
                }
                var map = defaults.getShieldOwnerMap()
                map.record(owner?.kind, under: keyed, at: Date().timeIntervalSince1970 * 1000)
                defaults.setShieldOwnerMap(map)
            }
            return owner?.kind.activityName
        }
        if let activityName = owningActivity {
            #if DEBUG
            logDebug("Owning activity: \(activityName)")
            #endif
            configDict = defaults.getShieldConfig(activityName: activityName)
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
