import { ConfigPlugin, createRunOncePlugin, withEntitlementsPlist } from 'expo/config-plugins';
import * as path from 'path';

import withScreenTimeTargets from './ios/withScreenTimeTargets';

const pkg = require('../../package.json');

// The package's `targets/` directory ships each extension's Swift sources +
// Info.plist. Resolve it from this plugin's built location: this file is
// `<pkg>/plugin/build/index.js`, so up two dirs is `<pkg>`, then `/targets`.
const TARGETS_ROOT = path.resolve(__dirname, '..', '..', 'targets');

/**
 * Expo Screen Time config plugin.
 *
 * Sets up the main app for the Screen Time APIs (App Groups + Family Controls
 * entitlements) and generates the iOS app-extension targets — ShieldConfiguration,
 * ShieldAction, DeviceActivityMonitor and DeviceActivityReport — directly via
 * Expo's Xcode config-plugin mods (no @bacons/apple-targets needed).
 *
 * Filter which targets to include via `expo.extra.expoScreenTime.targets`
 * (omit for all, empty array for none):
 *
 * @example
 * ```json
 * {
 *   "expo": {
 *     "extra": {
 *       "expoScreenTime": {
 *         "targets": ["DeviceActivityReportExtension", "ShieldConfigurationExtension"]
 *       }
 *     },
 *     "plugins": ["expo-screen-time"]
 *   }
 * }
 * ```
 *
 * Available targets:
 * - ShieldConfigurationExtension - Custom shield UI appearance
 * - ShieldActionExtension - Custom shield button behavior
 * - DeviceActivityMonitorExtension - Schedule monitoring (time-based blocking)
 * - DeviceActivityReportExtension - Usage analytics view
 */
const withExpoScreenTime: ConfigPlugin = (config) => {
  const bundleIdentifier = config.ios?.bundleIdentifier;
  if (!bundleIdentifier) {
    console.warn(
      '[expo-screen-time] Skipped: set `ios.bundleIdentifier` in your app config — ' +
        'it is required to derive the App Group and extension bundle ids.'
    );
    return config;
  }

  // App Group shared between the app and its extensions. The native code derives
  // the same `group.<bundleId>` (extensions strip their bundle-id suffix), so
  // this is not configurable — a custom value would silently not be read.
  const resolvedAppGroup = `group.${bundleIdentifier}`;

  // Main app: App Group + Family Controls entitlements.
  config = withEntitlementsPlist(config, (entConfig) => {
    const existingGroups =
      (entConfig.modResults['com.apple.security.application-groups'] as string[]) || [];
    if (!existingGroups.includes(resolvedAppGroup)) {
      entConfig.modResults['com.apple.security.application-groups'] = [
        ...existingGroups,
        resolvedAppGroup,
      ];
    }
    entConfig.modResults['com.apple.developer.family-controls'] = true;
    return entConfig;
  });

  // Generate the Screen Time extension targets.
  config = withScreenTimeTargets(config, {
    targetsRoot: TARGETS_ROOT,
    groupIdentifier: resolvedAppGroup,
  });

  return config;
};

export default createRunOncePlugin(withExpoScreenTime, pkg.name, pkg.version);
