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
           let appGroup = SharedDefaults.shared.getAppGroup() {
            if let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
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

        // The text colours the config leaves open: chosen for contrast against
        // a configured background (ShieldTextColors, the Android rule too),
        // else nil and the system-appearance defaults below, as before.
        let text = ShieldTextColors.resolve(
            background: color(config["backgroundColor"]),
            title: color(config, nested: "title", legacy: "titleColor"),
            subtitle: color(config, nested: "subtitle", legacy: "subtitleColor"),
            primaryButtonBackground: primaryButtonBackground(from: config),
            primaryButton: color(config, nested: "primaryButton", legacy: "primaryButtonColor"),
            secondaryButton: color(config, nested: "secondaryButton", legacy: "secondaryButtonColor")
        )
        let title = label(config, nested: "title", legacy: "title", color: text.title.map(UIColor.init) ?? .label)
        let subtitle = label(config, nested: "subtitle", legacy: "subtitle", color: text.subtitle.map(UIColor.init) ?? .secondaryLabel)
        let primaryButton = label(config, nested: "primaryButton", legacy: "primaryButtonLabel", color: text.primaryButton.map(UIColor.init) ?? .white)
        let secondaryButton = label(config, nested: "secondaryButton", legacy: "secondaryButtonLabel", color: text.secondaryButton.map(UIColor.init) ?? .systemBlue)

        // Build the configuration
        var shieldConfig = ShieldConfiguration()

        // Background
        if let bgColor = color(config["backgroundColor"]).map(UIColor.init) {
            shieldConfig = ShieldConfiguration(
                backgroundColor: bgColor,
                title: title,
                subtitle: subtitle,
                primaryButtonLabel: primaryButton,
                primaryButtonBackgroundColor: primaryButtonBackground(from: config).map(UIColor.init),
                secondaryButtonLabel: secondaryButton
            )
        } else {
            shieldConfig = ShieldConfiguration(
                title: title,
                subtitle: subtitle,
                primaryButtonLabel: primaryButton,
                primaryButtonBackgroundColor: primaryButtonBackground(from: config).map(UIColor.init),
                secondaryButtonLabel: secondaryButton
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

    /// A config colour string parsed (`#RRGGBB` / `#RRGGBBAA`); nil when absent or unparsable, which drops the colour, not the label.
    private func color(_ value: Any?) -> ShieldTextColors.RGBA? {
        return (value as? String).flatMap(ShieldTextColors.RGBA.init(hex:))
    }

    /// The `color` of the nested `{ text, color }` object under `nested`, else the legacy flat `legacy` key.
    private func color(_ config: [String: Any], nested: String, legacy: String) -> ShieldTextColors.RGBA? {
        if let dict = config[nested] as? [String: Any] {
            return color(dict["color"])
        }
        return color(config[legacy])
    }

    /// The primary button's own background: nested `primaryButton.backgroundColor`, else the legacy flat key.
    private func primaryButtonBackground(from config: [String: Any]) -> ShieldTextColors.RGBA? {
        if let dict = config["primaryButton"] as? [String: Any], let nested = color(dict["backgroundColor"]) {
            return nested
        }
        return color(config["primaryButtonBackgroundColor"])
    }

    /// The nested `{ text }` object under `nested` (no text: no label), else the legacy flat `legacy` string; drawn in `color`.
    private func label(_ config: [String: Any], nested: String, legacy: String, color: UIColor) -> ShieldConfiguration.Label? {
        if let dict = config[nested] as? [String: Any] {
            guard let text = dict["text"] as? String else { return nil }
            return ShieldConfiguration.Label(text: text, color: color)
        }
        if let text = config[legacy] as? String {
            return ShieldConfiguration.Label(text: text, color: color)
        }
        return nil
    }
}

// MARK: - UIColor Extension

extension UIColor {
    convenience init(_ rgba: ShieldTextColors.RGBA) {
        self.init(
            red: CGFloat(rgba.red) / 255.0,
            green: CGFloat(rgba.green) / 255.0,
            blue: CGFloat(rgba.blue) / 255.0,
            alpha: CGFloat(rgba.alpha) / 255.0
        )
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
