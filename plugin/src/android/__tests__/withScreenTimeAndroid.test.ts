import { AndroidConfig } from 'expo/config-plugins';
import * as path from 'path';

import {
  IS_MONITORING_TOOL,
  IS_MONITORING_TOOL_CHILD,
  applyScreenTimeAndroidManifest,
  getScreenTimeAndroidOptions,
} from '../withScreenTimeAndroid';

// The library's own manifest, merged by Gradle into every consumer: the
// entries the Android implementation always needs live there, not in the
// plugin (see withScreenTimeAndroid.ts). This test is their shape guard.
const LIBRARY_MANIFEST = path.resolve(__dirname, '..', '..', '..', '..', 'android', 'src', 'main', 'AndroidManifest.xml');

// A vanilla prebuild manifest, as `withAndroidManifest` hands it over.
function vanillaManifest(): AndroidConfig.Manifest.AndroidManifest {
  return {
    manifest: {
      $: { 'xmlns:android': 'http://schemas.android.com/apk/res/android' },
      'uses-permission': [{ $: { 'android:name': 'android.permission.INTERNET' } }],
      application: [
        {
          $: { 'android:name': '.MainApplication', 'android:label': '@string/app_name' },
          'meta-data': [{ $: { 'android:name': 'expo.modules.updates.ENABLED', 'android:value': 'false' } }],
          activity: [{ $: { 'android:name': '.MainActivity', 'android:exported': 'true' } }],
        },
      ],
    },
  } as any;
}

const metaData = (manifest: AndroidConfig.Manifest.AndroidManifest) =>
  AndroidConfig.Manifest.getMainApplicationOrThrow(manifest)['meta-data'] ?? [];

describe('getScreenTimeAndroidOptions', () => {
  it('defaults to no parental control', () => {
    expect(getScreenTimeAndroidOptions({})).toEqual({ parentalControl: false });
    expect(getScreenTimeAndroidOptions({ extra: { expoScreenTime: { targets: [] } } })).toEqual({ parentalControl: false });
    expect(getScreenTimeAndroidOptions({ extra: { expoScreenTime: { android: { parentalControl: true } } } })).toEqual({
      parentalControl: true,
    });
  });
});

describe('applyScreenTimeAndroidManifest', () => {
  it('leaves the manifest untouched without options', () => {
    const manifest = vanillaManifest();
    const before = JSON.stringify(manifest);
    applyScreenTimeAndroidManifest(manifest, { parentalControl: false });
    expect(JSON.stringify(manifest)).toBe(before);
  });

  it('declares the IsMonitoringTool flag for a parental-control app', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), { parentalControl: true });
    const items = metaData(manifest).filter((m) => m.$['android:name'] === IS_MONITORING_TOOL);
    expect(items).toHaveLength(1);
    expect(items[0].$['android:value']).toBe(IS_MONITORING_TOOL_CHILD);
    // The consumer's own entries stay.
    expect(metaData(manifest).some((m) => m.$['android:name'] === 'expo.modules.updates.ENABLED')).toBe(true);
  });

  it('is idempotent — re-applying changes nothing', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), { parentalControl: true });
    const once = JSON.stringify(manifest);
    applyScreenTimeAndroidManifest(manifest, { parentalControl: true });
    applyScreenTimeAndroidManifest(manifest, { parentalControl: true });
    expect(JSON.stringify(manifest)).toBe(once);
    expect(metaData(manifest).filter((m) => m.$['android:name'] === IS_MONITORING_TOOL)).toHaveLength(1);
  });

  it('removes the flag when the option is turned off (no stale entry on --no-clean)', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), { parentalControl: true });
    applyScreenTimeAndroidManifest(manifest, { parentalControl: false });
    expect(JSON.stringify(manifest)).toBe(JSON.stringify(vanillaManifest()));
  });
});

describe('the library manifest (merged into every consumer)', () => {
  let manifest: AndroidConfig.Manifest.AndroidManifest;
  beforeAll(async () => {
    manifest = await AndroidConfig.Manifest.readAndroidManifestAsync(LIBRARY_MANIFEST);
  });

  const names = (items: { $: Record<string, string | undefined> }[] | undefined) =>
    (items ?? []).map((i) => i.$['android:name']);
  // The library's <application> has no android:name (the consumer's wins at
  // merge time), so getMainApplicationOrThrow does not apply.
  const application = () => manifest.manifest.application![0] as any;

  it('requests exactly the permissions the engine needs, never QUERY_ALL_PACKAGES', () => {
    expect(names(manifest.manifest['uses-permission']).sort()).toEqual(
      [
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
        'android.permission.PACKAGE_USAGE_STATS',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.SYSTEM_ALERT_WINDOW',
      ].sort()
    );
    const usageStats = manifest.manifest['uses-permission']!.find(
      (p) => p.$['android:name'] === 'android.permission.PACKAGE_USAGE_STATS'
    ) as any;
    expect(usageStats.$['tools:ignore']).toBe('ProtectedPermissions');
  });

  it('declares the launcher <queries> intent', () => {
    const queries = (manifest.manifest as any).queries as any[];
    expect(queries).toHaveLength(1);
    const intent = queries[0].intent[0];
    expect(names(intent.action)).toEqual(['android.intent.action.MAIN']);
    expect(names(intent.category)).toEqual(['android.intent.category.LAUNCHER']);
  });

  it('declares the engine service as a specialUse FGS in the :screentime process, with its subtype', () => {
    const services = application().service as any[];
    expect(services).toHaveLength(1);
    const service = services[0];
    expect(service.$['android:name']).toBe('expo.modules.screentime.ScreenTimeEngineService');
    expect(service.$['android:exported']).toBe('false');
    expect(service.$['android:foregroundServiceType']).toBe('specialUse');
    expect(service.$['android:process']).toBe(':screentime');
    expect(names(service.property)).toEqual(['android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE']);
    expect(service.property[0].$['android:value']).toMatch(/\S/);
  });

  it('declares the restart and device-admin receivers', () => {
    const receivers = (application().receiver ?? []) as any[];
    const restart = receivers.find((r) => r.$['android:name'] === 'expo.modules.screentime.ScreenTimeRestartReceiver');
    expect(restart.$['android:process']).toBe(':screentime');
    expect(names(restart['intent-filter'][0].action).sort()).toEqual([
      'android.intent.action.BOOT_COMPLETED',
      'android.intent.action.MY_PACKAGE_REPLACED',
    ]);
    const admin = receivers.find((r) => r.$['android:name'] === 'expo.modules.screentime.ScreenTimeDeviceAdminReceiver');
    expect(admin.$['android:permission']).toBe('android.permission.BIND_DEVICE_ADMIN');
    expect(names(admin['meta-data'])).toEqual(['android.app.device_admin']);
    expect(names(admin['intent-filter'][0].action)).toEqual(['android.app.action.DEVICE_ADMIN_ENABLED']);
  });
});
