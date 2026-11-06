import { AndroidConfig, ConfigPlugin, withAndroidManifest } from 'expo/config-plugins';

/**
 * The contract's `ScreenTimeFeature` names (`src/ExpoScreenTime.types.ts`),
 * repeated here because the plugin does not import the module's types; the
 * jest test holds the two lists equal.
 */
export const ANDROID_FEATURES = [
  'blocking',
  'monitoring',
  'usageReports',
  'shieldOpenApp',
  'appRemovalProtection',
  'webDomainBlocking',
] as const;

export type AndroidFeature = (typeof ANDROID_FEATURES)[number];

/**
 * Android options, under `expo.extra.expoScreenTime.android`.
 */
export type ScreenTimeAndroidOptions = {
  /**
   * The app is a parental-control app: declares Google Play's mandatory
   * `isMonitoringTool` manifest flag (Malware / Stalkerware policy: apps that
   * monitor a child must declare it in every version code). Default `false`.
   */
  parentalControl?: boolean;
  /**
   * The features the app uses, by the contract's `ScreenTimeFeature` names.
   * The library's own manifest declares everything every feature needs; the
   * plugin removes (`tools:node="remove"`) each component and permission no
   * listed feature needs, so an app that only reads usage data is not
   * listed under Settings › Special app access › Picture-in-picture or
   * Device admin apps, and has no `specialUse` foreground service to
   * justify in Play review. The mapping is `ANDROID_MANIFEST_GATES`. A
   * removed permission is removed as `android.blockedPermissions` removes
   * one: from the merged manifest, whichever library declared it. What your
   * own manifest requests stays (`android.permissions` always wins; the Expo
   * template itself requests `SYSTEM_ALERT_WINDOW`, which
   * `android.blockedPermissions` drops). At runtime the module reads what
   * the merged manifest declares: `getCapabilities().blocking` is `"none"`
   * without the shield and the functions of a removed feature reject
   * `UNSUPPORTED`.
   *
   * Absent: every feature (the library manifest as it is). An empty array
   * keeps only what the selection store and the picker need. An unknown
   * name fails the prebuild.
   *
   * Android only: the iOS build is shaped by `expo.extra.expoScreenTime.targets`.
   */
  features?: AndroidFeature[];
};

/** The options with their defaults applied. */
export type ResolvedScreenTimeAndroidOptions = {
  parentalControl: boolean;
  features: AndroidFeature[];
};

/**
 * The name of Google Play's monitoring-tool flag and its value for a
 * parental-control app, spelled as the manifest example of the Play Console
 * help page writes them (https://support.google.com/googleplay/android-developer/answer/12955211):
 * `<meta-data android:name="isMonitoringTool" android:value="child_monitoring" />`,
 * the values being `child_monitoring`, `enterprise_management` or `other`. A
 * Screen Time consumer is the first kind. Meta-data names are compared as
 * written, so the name follows that example (lower-case `i`), not the
 * "IsMonitoringTool" of the Malware policy's prose.
 */
export const IS_MONITORING_TOOL = 'isMonitoringTool';
export const IS_MONITORING_TOOL_CHILD = 'child_monitoring';
/**
 * The spelling earlier versions of this plugin wrote. Removed on every run,
 * so a `--no-clean` prebuild after an upgrade keeps one flag, spelled right.
 */
export const LEGACY_IS_MONITORING_TOOL = 'IsMonitoringTool';


/** A manifest element of the library that only some features need. */
export type ManifestGate = {
  /** Where the element lives: under `<manifest>` (permissions) or `<application>` (components). */
  kind: 'uses-permission' | 'activity' | 'service' | 'receiver';
  /** The element's `android:name`, fully qualified, as the library manifest declares it. */
  name: string;
  /** The features that need it; the element is removed when none of them is listed. */
  features: readonly AndroidFeature[];
};

const ENGINE = ['blocking', 'monitoring'] as const;

/**
 * THE feature → manifest mapping, the one place to extend when the library
 * manifest (`android/src/main/AndroidManifest.xml`) gains a component or a
 * permission; the jest test fails until every element of that manifest has
 * a row here. Everything not listed (the `<queries>` launcher intent) stays
 * in every build.
 *
 * What each feature really uses at runtime:
 * - `blocking` (`blockSelection`, schedules that shield, thresholds that
 *   shield, the temporary unlock, the shield's buttons, the enforcement
 *   gaps): the engine service in its `:screentime` process, the restart
 *   receiver (boot, update, the engine's wake alarm), the shield Activity
 *   and its PiP evictor, the device admin (force-stop friction), and the
 *   permissions those need (`SYSTEM_ALERT_WINDOW` is the shield's
 *   background-activity-launch exemption).
 * - `monitoring` alone (schedules and thresholds that only record events):
 *   the engine and its receiver, without the shield, the overlay permission
 *   or the device admin. A `startMonitoring` that would shield
 *   (`selectionId`, `blockOnThreshold`) rejects `UNSUPPORTED` in such a build.
 * - `usageReports`: usage access only (the report view and its renderer
 *   are plain Kotlin in the library, nothing in the manifest).
 * - `appRemovalProtection`: the device admin.
 * - `shieldOpenApp` (how a shield reaches the host app: direct on Android)
 *   and `webDomainBlocking` (not implemented on Android) need nothing of
 *   their own.
 */
export const ANDROID_MANIFEST_GATES: readonly ManifestGate[] = [
  { kind: 'service', name: 'expo.modules.screentime.ScreenTimeEngineService', features: ENGINE },
  { kind: 'receiver', name: 'expo.modules.screentime.ScreenTimeRestartReceiver', features: ENGINE },
  { kind: 'activity', name: 'expo.modules.screentime.ShieldActivity', features: ['blocking'] },
  { kind: 'activity', name: 'expo.modules.screentime.PipEvictorActivity', features: ['blocking'] },
  {
    kind: 'receiver',
    name: 'expo.modules.screentime.ScreenTimeDeviceAdminReceiver',
    features: ['blocking', 'appRemovalProtection'],
  },
  {
    kind: 'uses-permission',
    name: 'android.permission.PACKAGE_USAGE_STATS',
    features: ['blocking', 'monitoring', 'usageReports'],
  },
  { kind: 'uses-permission', name: 'android.permission.SYSTEM_ALERT_WINDOW', features: ['blocking'] },
  { kind: 'uses-permission', name: 'android.permission.FOREGROUND_SERVICE', features: ENGINE },
  { kind: 'uses-permission', name: 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE', features: ENGINE },
  { kind: 'uses-permission', name: 'android.permission.POST_NOTIFICATIONS', features: ENGINE },
  { kind: 'uses-permission', name: 'android.permission.RECEIVE_BOOT_COMPLETED', features: ENGINE },
];

/** The gates `features` leave out: what the plugin removes from the merged manifest. */
export function removedGates(features: readonly AndroidFeature[]): ManifestGate[] {
  return ANDROID_MANIFEST_GATES.filter((gate) => !gate.features.some((f) => features.includes(f)));
}

/**
 * Reads the Android options from the app config (absent → defaults). An
 * unknown feature name, or a `features` that is not an array of strings,
 * throws: a typo must fail the prebuild, not silently keep a component.
 */
export function getScreenTimeAndroidOptions(config: {
  extra?: Record<string, any>;
}): ResolvedScreenTimeAndroidOptions {
  const options = (config.extra?.expoScreenTime?.android ?? {}) as ScreenTimeAndroidOptions;
  return {
    parentalControl: options.parentalControl === true,
    features: resolveFeatures(options.features),
  };
}

function resolveFeatures(value: unknown): AndroidFeature[] {
  if (value === undefined || value === null) return [...ANDROID_FEATURES];
  if (!Array.isArray(value)) {
    throw new Error(
      `[expo-screen-time] \`expo.extra.expoScreenTime.android.features\` must be an array of feature names ` +
        `(${ANDROID_FEATURES.join(', ')}), got ${JSON.stringify(value)}`
    );
  }
  const unknown = value.filter((name) => !ANDROID_FEATURES.includes(name));
  if (unknown.length > 0) {
    throw new Error(
      `[expo-screen-time] Unknown feature name(s) in \`expo.extra.expoScreenTime.android.features\`: ` +
        `${unknown.map((n) => JSON.stringify(n)).join(', ')}. Valid names: ${ANDROID_FEATURES.join(', ')}.`
    );
  }
  return [...new Set(value as AndroidFeature[])];
}

type ManifestElement = { $: Record<string, string | undefined> };

const TOOLS_NODE = 'tools:node';

/** `android.permissions` as the base `withPermissions` mod writes them (a bare name gets the `android.permission.` prefix). */
export function normalizePermissionNames(permissions: readonly string[] | undefined): string[] {
  return (permissions ?? []).filter(Boolean).map((p) => (p.includes('.') ? p : `android.permission.${p}`));
}

/** The node holding the gate's kind of element: `<manifest>` for permissions, `<application>` for components. */
function parentOf(manifest: AndroidConfig.Manifest.AndroidManifest, gate: ManifestGate): Record<string, any> {
  return gate.kind === 'uses-permission'
    ? manifest.manifest
    : AndroidConfig.Manifest.getMainApplicationOrThrow(manifest);
}

/**
 * A removal node for the gate. The merger needs a plain `tools:node="remove"`
 * (a `tools:selector` would scope it to this library, but the merger then
 * keeps the marker itself in the output as a bare element: measured, the
 * engine service came back without its attributes). A consumer's own
 * `android.blockedPermissions` node has the same shape; those names are
 * passed in and never touched.
 */
const isRemoveNode = (element: ManifestElement, gate: ManifestGate) =>
  element.$['android:name'] === gate.name && element.$[TOOLS_NODE] === 'remove';

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
 * which is idempotent by construction. The app manifest gets only optional
 * entries: the `isMonitoringTool` flag, and one `tools:node="remove"` per
 * library element the chosen `features` do not need (`ANDROID_MANIFEST_GATES`),
 * which Gradle's merger then drops from the merged manifest together with
 * the element, from whichever library manifest declared it (the semantics
 * of Expo's `android.blockedPermissions`). An element the app manifest
 * declares itself is left alone (a request and a removal of the same
 * element in one manifest is a merger error, and the request means
 * something else wants it); `declaredPermissions` (`android.permissions`)
 * counts as declared too, so a request added after a removal was written
 * replaces it on the next `--no-clean` run (the base mod skips a name it
 * already sees). A name in `blockedPermissions` is the consumer's own
 * removal, written by Expo's base mod before this one runs: never touched.
 */
export function applyScreenTimeAndroidManifest(
  manifest: AndroidConfig.Manifest.AndroidManifest,
  options: ResolvedScreenTimeAndroidOptions,
  declaredPermissions: readonly string[] = [],
  blockedPermissions: readonly string[] = []
): AndroidConfig.Manifest.AndroidManifest {
  const application = AndroidConfig.Manifest.getMainApplicationOrThrow(manifest);
  // The old spelling goes whatever the option (LEGACY_IS_MONITORING_TOOL).
  AndroidConfig.Manifest.removeMetaDataItemFromMainApplication(application, LEGACY_IS_MONITORING_TOOL);
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

  const removed = removedGates(options.features);
  for (const gate of ANDROID_MANIFEST_GATES) {
    const parent = parentOf(manifest, gate);
    const elements: ManifestElement[] | undefined = Array.isArray(parent[gate.kind]) ? parent[gate.kind] : undefined;
    if (gate.kind === 'uses-permission' && blockedPermissions.includes(gate.name)) continue; // the consumer's own block
    const requestedByApp = gate.kind === 'uses-permission' && declaredPermissions.includes(gate.name);
    if (!removed.includes(gate) || requestedByApp) {
      // Needed (again), or requested by the app: drop the removal this
      // plugin wrote, if any. An array left empty goes away so the manifest
      // reads as before the option; a request the base mod skipped because
      // our node was in its way is written in its place.
      if (elements?.some((e) => isRemoveNode(e, gate))) {
        const kept = elements.filter((e) => !isRemoveNode(e, gate));
        if (requestedByApp && !kept.some((e) => e.$['android:name'] === gate.name)) kept.push({ $: { 'android:name': gate.name } });
        if (kept.length > 0) parent[gate.kind] = kept;
        else delete parent[gate.kind];
      }
      continue;
    }
    // Written already, or declared by the app itself (then it stays).
    if (elements?.some((e) => e.$['android:name'] === gate.name)) continue;
    AndroidConfig.Manifest.ensureToolsAvailable(manifest);
    parent[gate.kind] = [...(elements ?? []), { $: { 'android:name': gate.name, [TOOLS_NODE]: 'remove' } }];
  }
  return manifest;
}

const withScreenTimeAndroid: ConfigPlugin = (config) => {
  const options = getScreenTimeAndroidOptions(config);
  return withAndroidManifest(config, (modConfig) => {
    modConfig.modResults = applyScreenTimeAndroidManifest(
      modConfig.modResults,
      options,
      normalizePermissionNames(modConfig.android?.permissions),
      normalizePermissionNames(modConfig.android?.blockedPermissions)
    );
    return modConfig;
  });
};

export default withScreenTimeAndroid;
