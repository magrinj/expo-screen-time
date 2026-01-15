import { ConfigPlugin, createRunOncePlugin, withEntitlementsPlist } from 'expo/config-plugins';

const pkg = require('../../package.json');

export interface ExpoScreenTimePluginOptions {
  /**
   * Custom App Group identifier for sharing data between app and extensions.
   * If not provided, defaults to `group.{bundleIdentifier}`
   */
  appGroup?: string;
}

/**
 * Expo Screen Time config plugin.
 *
 * This plugin sets up the required entitlements for Screen Time API:
 * - App Groups (for sharing data between app and extensions)
 * - Family Controls
 *
 * To include extension targets (DeviceActivityReport, ShieldConfiguration, etc.),
 * use @bacons/apple-targets with libraryTargets pointing to expo-screen-time.
 *
 * You can filter which targets to include via expo.extra.expoScreenTime.targets:
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
 *     "plugins": [
 *       "expo-screen-time",
 *       ["@bacons/apple-targets", { "libraryTargets": ["expo-screen-time"] }]
 *     ]
 *   }
 * }
 * ```
 *
 * Available targets:
 * - DeviceActivityReportExtension - Usage analytics view
 * - DeviceActivityMonitorExtension - Schedule monitoring (time-based blocking)
 * - ShieldConfigurationExtension - Custom shield UI appearance
 * - ShieldActionExtension - Custom shield button behavior
 */
const withExpoScreenTime: ConfigPlugin<ExpoScreenTimePluginOptions | void> = (config, props = {}) => {
  const { appGroup } = props || {};
  const resolvedAppGroup = appGroup ?? `group.${config.ios?.bundleIdentifier}`;

  // Add entitlements required for Screen Time API
  config = withEntitlementsPlist(config, (entConfig) => {
    // Add App Group entitlement for sharing data with extensions
    const existingGroups = (entConfig.modResults['com.apple.security.application-groups'] as string[]) || [];
    if (!existingGroups.includes(resolvedAppGroup)) {
      entConfig.modResults['com.apple.security.application-groups'] = [
        ...existingGroups,
        resolvedAppGroup,
      ];
    }

    // Add Family Controls entitlement
    entConfig.modResults['com.apple.developer.family-controls'] = true;

    return entConfig;
  });

  return config;
};

export default createRunOncePlugin(withExpoScreenTime, pkg.name, pkg.version);
