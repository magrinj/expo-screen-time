import { AndroidConfig, ConfigPlugin, withAndroidManifest } from 'expo/config-plugins';

/**
 * Android options, under `expo.extra.expoScreenTime.android`.
 */
export type ScreenTimeAndroidOptions = {
  /**
   * The app is a parental-control app: declares Google Play's mandatory
   * `IsMonitoringTool` manifest flag (Malware / Stalkerware policy: apps that
   * monitor a child must declare it in every version code). Default `false`.
   */
  parentalControl?: boolean;
};

/**
 * The name of Google Play's monitoring-tool flag and its value for a
 * parental-control app, from the Play Console help page "Use of the
 * IsMonitoringTool flag" (https://support.google.com/googleplay/android-developer/answer/12955211):
 * `child_monitoring`, `enterprise_management` or `other`. A Screen Time
 * consumer is the first kind.
 */
export const IS_MONITORING_TOOL = 'IsMonitoringTool';
export const IS_MONITORING_TOOL_CHILD = 'child_monitoring';

/**
 * Reads the Android options from the app config (absent → defaults).
 */
export function getScreenTimeAndroidOptions(config: {
  extra?: Record<string, any>;
}): Required<ScreenTimeAndroidOptions> {
  const options = (config.extra?.expoScreenTime?.android ?? {}) as ScreenTimeAndroidOptions;
  return { parentalControl: options.parentalControl === true };
}

/**
 * The pure manifest mutation, exported for the jest test (the pattern of
 * `applyTargetToXcodeProject`): applies the options to the manifest object
 * and returns it. Idempotent: every entry is looked up by name before it is
 * added, and removed when its option is off, so a re-run with the same
 * options changes nothing and a flipped option does not leave a stale entry.
 *
 * Everything the implementation always needs (permissions, the `<queries>`
 * launcher intent, the engine service, the receivers) is declared in the
 * library's own `android/src/main/AndroidManifest.xml` and merged by Gradle,
 * which is idempotent by construction. Only optional, app-level entries are
 * written here.
 */
export function applyScreenTimeAndroidManifest(
  manifest: AndroidConfig.Manifest.AndroidManifest,
  options: Required<ScreenTimeAndroidOptions>
): AndroidConfig.Manifest.AndroidManifest {
  const application = AndroidConfig.Manifest.getMainApplicationOrThrow(manifest);
  if (options.parentalControl) {
    // Replaces an existing value (a consumer who declared `other` by hand
    // keeps one entry, with the value the option implies).
    AndroidConfig.Manifest.addMetaDataItemToMainApplication(
      application,
      IS_MONITORING_TOOL,
      IS_MONITORING_TOOL_CHILD
    );
  } else {
    AndroidConfig.Manifest.removeMetaDataItemFromMainApplication(application, IS_MONITORING_TOOL);
  }
  return manifest;
}

const withScreenTimeAndroid: ConfigPlugin = (config) => {
  const options = getScreenTimeAndroidOptions(config);
  return withAndroidManifest(config, (modConfig) => {
    modConfig.modResults = applyScreenTimeAndroidManifest(modConfig.modResults, options);
    return modConfig;
  });
};

export default withScreenTimeAndroid;
