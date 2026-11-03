import { AndroidConfig } from 'expo/config-plugins';
import * as path from 'path';

import * as fs from 'fs';

import {
  ANDROID_FEATURES,
  ANDROID_MANIFEST_GATES,
  AndroidFeature,
  IS_MONITORING_TOOL,
  IS_MONITORING_TOOL_CHILD,
  ManifestGate,
  ResolvedScreenTimeAndroidOptions,
  applyScreenTimeAndroidManifest,
  getScreenTimeAndroidOptions,
  normalizePermissionNames,
  removedGates,
} from '../withScreenTimeAndroid';

// The library's own manifest, merged by Gradle into every consumer: the
// entries the Android implementation always needs live there, not in the
// plugin (see withScreenTimeAndroid.ts). This test is their shape guard.
const LIBRARY_MANIFEST = path.resolve(__dirname, '..', '..', '..', '..', 'android', 'src', 'main', 'AndroidManifest.xml');
const TYPES = path.resolve(__dirname, '..', '..', '..', '..', 'src', 'ExpoScreenTime.types.ts');

const ALL: AndroidFeature[] = [...ANDROID_FEATURES];
const options = (features: AndroidFeature[], parentalControl = false): ResolvedScreenTimeAndroidOptions => ({ parentalControl, features });

// A vanilla prebuild manifest, as `withAndroidManifest` hands it over (the
// bare template declares the tools namespace).
function vanillaManifest(): AndroidConfig.Manifest.AndroidManifest {
  return {
    manifest: {
      $: { 'xmlns:android': 'http://schemas.android.com/apk/res/android', 'xmlns:tools': 'http://schemas.android.com/tools' },
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
  it('defaults to no parental control and every feature', () => {
    expect(getScreenTimeAndroidOptions({})).toEqual({ parentalControl: false, features: ALL });
    expect(getScreenTimeAndroidOptions({ extra: { expoScreenTime: { targets: [] } } })).toEqual({ parentalControl: false, features: ALL });
    expect(getScreenTimeAndroidOptions({ extra: { expoScreenTime: { android: { parentalControl: true } } } })).toEqual({
      parentalControl: true,
      features: ALL,
    });
  });

  it('reads the features, order kept, duplicates dropped, an empty array allowed', () => {
    const read = (features: unknown) => getScreenTimeAndroidOptions({ extra: { expoScreenTime: { android: { features } } } }).features;
    expect(read(['usageReports'])).toEqual(['usageReports']);
    expect(read(['monitoring', 'blocking', 'monitoring'])).toEqual(['monitoring', 'blocking']);
    expect(read([])).toEqual([]);
  });

  it('fails the prebuild on an unknown feature name or a non-array (typo protection)', () => {
    const read = (features: unknown) => () => getScreenTimeAndroidOptions({ extra: { expoScreenTime: { android: { features } } } });
    expect(read(['blocking', 'usageReport'])).toThrow(/Unknown feature name\(s\).*"usageReport".*Valid names: blocking, monitoring, usageReports/);
    expect(read(['Blocking'])).toThrow(/"Blocking"/);
    expect(read([42])).toThrow(/Unknown feature name/);
    expect(read('blocking')).toThrow(/must be an array of feature names/);
    expect(read({ blocking: true })).toThrow(/must be an array/);
  });

  it('names exactly the contract\'s ScreenTimeFeature union', () => {
    const types = fs.readFileSync(TYPES, 'utf8');
    const union = types.slice(types.indexOf('export type ScreenTimeFeature ='));
    const names = [...union.slice(0, union.indexOf(';')).matchAll(/\| "(\w+)"/g)].map((m) => m[1]);
    expect(names).toEqual(ALL);
  });
});

describe('ANDROID_MANIFEST_GATES', () => {
  it('lists every feature-gated element exactly once, by its fully qualified name', () => {
    const keys = ANDROID_MANIFEST_GATES.map((g) => `${g.kind}#${g.name}`);
    expect(new Set(keys).size).toBe(keys.length);
    for (const gate of ANDROID_MANIFEST_GATES) {
      expect(gate.name).toMatch(/^(android\.permission|expo\.modules\.screentime)\./);
      expect(gate.features.length).toBeGreaterThan(0);
    }
  });

  it('keeps everything with every feature, removes every gate with none', () => {
    expect(removedGates(ALL)).toEqual([]);
    expect(removedGates([])).toEqual(ANDROID_MANIFEST_GATES);
  });

  it('maps each feature to what it uses at runtime', () => {
    const removedNames = (features: AndroidFeature[]) => removedGates(features).map((g) => g.name.replace(/^.*\./, ''));
    // Blocking needs everything: the engine, the shield, the PiP evictor, the device admin.
    expect(removedNames(['blocking'])).toEqual([]);
    // Monitoring alone: the engine and its receiver, no shield, no overlay, no device admin.
    expect(removedNames(['monitoring'])).toEqual([
      'ShieldActivity',
      'PipEvictorActivity',
      'ScreenTimeDeviceAdminReceiver',
      'SYSTEM_ALERT_WINDOW',
    ]);
    // Usage reports alone: usage access only.
    expect(removedNames(['usageReports'])).toEqual([
      'ScreenTimeEngineService',
      'ScreenTimeRestartReceiver',
      'ShieldActivity',
      'PipEvictorActivity',
      'ScreenTimeDeviceAdminReceiver',
      'SYSTEM_ALERT_WINDOW',
      'FOREGROUND_SERVICE',
      'FOREGROUND_SERVICE_SPECIAL_USE',
      'POST_NOTIFICATIONS',
      'RECEIVE_BOOT_COMPLETED',
    ]);
    // App removal protection alone: the device admin.
    expect(removedNames(['appRemovalProtection'])).not.toContain('ScreenTimeDeviceAdminReceiver');
    expect(removedNames(['appRemovalProtection'])).toContain('ScreenTimeEngineService');
    // Features with nothing of their own on Android remove as much as nothing.
    expect(removedNames(['shieldOpenApp', 'webDomainBlocking'])).toEqual(removedNames([]));
  });
});

describe('applyScreenTimeAndroidManifest', () => {
  it('leaves the manifest untouched without options', () => {
    const manifest = vanillaManifest();
    const before = JSON.stringify(manifest);
    applyScreenTimeAndroidManifest(manifest, options(ALL));
    expect(JSON.stringify(manifest)).toBe(before);
  });

  it('declares the IsMonitoringTool flag for a parental-control app', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), options(ALL, true));
    const items = metaData(manifest).filter((m) => m.$['android:name'] === IS_MONITORING_TOOL);
    expect(items).toHaveLength(1);
    expect(items[0].$['android:value']).toBe(IS_MONITORING_TOOL_CHILD);
    // The consumer's own entries stay.
    expect(metaData(manifest).some((m) => m.$['android:name'] === 'expo.modules.updates.ENABLED')).toBe(true);
  });

  it('is idempotent — re-applying changes nothing', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), options(ALL, true));
    const once = JSON.stringify(manifest);
    applyScreenTimeAndroidManifest(manifest, options(ALL, true));
    applyScreenTimeAndroidManifest(manifest, options(ALL, true));
    expect(JSON.stringify(manifest)).toBe(once);
    expect(metaData(manifest).filter((m) => m.$['android:name'] === IS_MONITORING_TOOL)).toHaveLength(1);
  });

  it('removes the flag when the option is turned off (no stale entry on --no-clean)', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), options(ALL, true));
    applyScreenTimeAndroidManifest(manifest, options(ALL));
    expect(JSON.stringify(manifest)).toBe(JSON.stringify(vanillaManifest()));
  });
});

describe('applyScreenTimeAndroidManifest — features', () => {
  const removeNodes = (manifest: AndroidConfig.Manifest.AndroidManifest): string[] => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(manifest) as any;
    const nodes = [
      ...(manifest.manifest['uses-permission'] ?? []).map((e: any) => ['uses-permission', e] as const),
      ...(['activity', 'service', 'receiver'] as const).flatMap((kind) => ((app[kind] ?? []) as any[]).map((e) => [kind, e] as const)),
    ];
    return nodes.filter(([, e]) => e.$['tools:node'] === 'remove').map(([kind, e]) => `${kind}#${e.$['android:name']}`);
  };
  const expected = (gates: readonly ManifestGate[]) => gates.map((g) => `${g.kind}#${g.name}`);

  it('writes nothing with every feature (the default): existing consumers see no change', () => {
    const manifest = vanillaManifest();
    const before = JSON.stringify(manifest);
    applyScreenTimeAndroidManifest(manifest, options(ALL));
    expect(JSON.stringify(manifest)).toBe(before);
    expect(JSON.stringify(applyScreenTimeAndroidManifest(vanillaManifest(), options(['blocking'])))).toBe(before);
  });

  it.each([[['usageReports']], [['monitoring']], [['appRemovalProtection']], [['monitoring', 'usageReports']], [[]]] as AndroidFeature[][][])(
    'writes one tools:node="remove" per gate the features %j leave out, nothing else',
    (features) => {
      const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), options(features));
      expect(removeNodes(manifest).sort()).toEqual(expected(removedGates(features)).sort());
      // The consumer's own entries stay, untouched.
      expect(manifest.manifest['uses-permission']!.some((p) => p.$['android:name'] === 'android.permission.INTERNET' && !(p.$ as any)['tools:node'])).toBe(true);
      const app = AndroidConfig.Manifest.getMainApplicationOrThrow(manifest);
      expect(app.activity!.some((a) => a.$['android:name'] === '.MainActivity')).toBe(true);
      expect(manifest.manifest.$['xmlns:tools']).toBe('http://schemas.android.com/tools');
    }
  );

  it('is idempotent for a reduced feature set', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), options(['usageReports']));
    const once = JSON.stringify(manifest);
    applyScreenTimeAndroidManifest(manifest, options(['usageReports']));
    applyScreenTimeAndroidManifest(manifest, options(['usageReports']));
    expect(JSON.stringify(manifest)).toBe(once);
  });

  it('removes every entry it wrote when the features change back (no stale entry on --no-clean)', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), options(['usageReports']));
    applyScreenTimeAndroidManifest(manifest, options(ALL));
    expect(JSON.stringify(manifest)).toBe(JSON.stringify(vanillaManifest()));
    // And follows a change from one subset to another.
    applyScreenTimeAndroidManifest(manifest, options(['monitoring']));
    expect(removeNodes(manifest).sort()).toEqual(expected(removedGates(['monitoring'])).sort());
    applyScreenTimeAndroidManifest(manifest, options([]));
    expect(removeNodes(manifest).sort()).toEqual(expected(ANDROID_MANIFEST_GATES).sort());
    applyScreenTimeAndroidManifest(manifest, options(ALL));
    expect(JSON.stringify(manifest)).toBe(JSON.stringify(vanillaManifest()));
  });

  it('never removes an element the app manifest declares itself', () => {
    const manifest = vanillaManifest();
    // expo-notifications (or `android.permissions`) requests these for its own reasons.
    manifest.manifest['uses-permission']!.push({ $: { 'android:name': 'android.permission.POST_NOTIFICATIONS' } }, { $: { 'android:name': 'android.permission.RECEIVE_BOOT_COMPLETED' } });
    const before = JSON.parse(JSON.stringify(manifest));
    applyScreenTimeAndroidManifest(manifest, options(['usageReports']));
    const names = (m: any) => m.manifest['uses-permission'].map((p: any) => `${p.$['android:name']}${p.$['tools:node'] ? '!' : ''}`);
    expect(names(manifest)).toEqual([
      ...names(before),
      'android.permission.SYSTEM_ALERT_WINDOW!',
      'android.permission.FOREGROUND_SERVICE!',
      'android.permission.FOREGROUND_SERVICE_SPECIAL_USE!',
    ]);
    // ...and does not take it away when the features change back.
    applyScreenTimeAndroidManifest(manifest, options(ALL));
    expect(JSON.stringify(manifest)).toBe(JSON.stringify(before));
  });

  it('writes plain removals: the merger drops the node with the element (a tools:selector would leave a bare marker behind)', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), options([]));
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(manifest) as any;
    const nodes = [...manifest.manifest['uses-permission']!, ...app.activity, ...app.service, ...app.receiver].filter((e: any) => e.$['tools:node']);
    expect(nodes).toHaveLength(ANDROID_MANIFEST_GATES.length);
    for (const node of nodes) expect(Object.keys((node as any).$).sort()).toEqual(['android:name', 'tools:node']);
  });

  it('never touches a consumer\'s own android.blockedPermissions node (same shape, theirs)', () => {
    const blocked = ['android.permission.SYSTEM_ALERT_WINDOW'];
    const block = { $: { 'android:name': 'android.permission.SYSTEM_ALERT_WINDOW', 'tools:node': 'remove' } };
    const manifest = vanillaManifest();
    manifest.manifest['uses-permission']!.push(block as any);
    const before = JSON.stringify(manifest);
    // Features absent: the consumer's block survives every prebuild (today's manifest, byte for byte).
    applyScreenTimeAndroidManifest(manifest, options(ALL), [], blocked);
    expect(JSON.stringify(manifest)).toBe(before);
    // A subset: the name is blocked already, nothing is added for it; the block is still theirs afterwards.
    applyScreenTimeAndroidManifest(manifest, options(['usageReports']), [], blocked);
    expect(manifest.manifest['uses-permission']!.filter((p) => p.$['android:name'] === 'android.permission.SYSTEM_ALERT_WINDOW')).toEqual([block]);
    applyScreenTimeAndroidManifest(manifest, options(ALL), [], blocked);
    expect(JSON.stringify(manifest)).toBe(before);
  });

  it('gives way to a permission later added to android.permissions (the base mod skips a name our node holds)', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), options(['usageReports']));
    // Next --no-clean run: the consumer added POST_NOTIFICATIONS to android.permissions; the base mod ran first and skipped it.
    applyScreenTimeAndroidManifest(manifest, options(['usageReports']), normalizePermissionNames(['POST_NOTIFICATIONS']));
    const notifications = manifest.manifest['uses-permission']!.filter((p) => p.$['android:name'] === 'android.permission.POST_NOTIFICATIONS');
    expect(notifications).toEqual([{ $: { 'android:name': 'android.permission.POST_NOTIFICATIONS' } }]);
    const once = JSON.stringify(manifest);
    applyScreenTimeAndroidManifest(manifest, options(['usageReports']), ['android.permission.POST_NOTIFICATIONS']);
    expect(JSON.stringify(manifest)).toBe(once);
    expect(normalizePermissionNames(['POST_NOTIFICATIONS', 'com.example.CUSTOM', ''])).toEqual(['android.permission.POST_NOTIFICATIONS', 'com.example.CUSTOM']);
  });

  it('declares the tools namespace when the manifest lacks it', () => {
    const manifest = vanillaManifest();
    delete (manifest.manifest.$ as any)['xmlns:tools'];
    applyScreenTimeAndroidManifest(manifest, options(ALL));
    expect((manifest.manifest.$ as any)['xmlns:tools']).toBeUndefined();
    applyScreenTimeAndroidManifest(manifest, options(['usageReports']));
    expect(manifest.manifest.$['xmlns:tools']).toBe('http://schemas.android.com/tools');
  });

  it('composes with parentalControl', () => {
    const manifest = applyScreenTimeAndroidManifest(vanillaManifest(), options(['usageReports'], true));
    expect(metaData(manifest).some((m) => m.$['android:name'] === IS_MONITORING_TOOL)).toBe(true);
    expect(removeNodes(manifest).length).toBe(removedGates(['usageReports']).length);
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

  it('has a row in ANDROID_MANIFEST_GATES for every permission and component (extend the table when adding one)', () => {
    const declared = [
      ...names(manifest.manifest['uses-permission']).map((n) => `uses-permission#${n}`),
      ...(['activity', 'service', 'receiver'] as const).flatMap((kind) => names(application()[kind]).map((n) => `${kind}#${n}`)),
    ].sort();
    expect(ANDROID_MANIFEST_GATES.map((g) => `${g.kind}#${g.name}`).sort()).toEqual(declared);
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
