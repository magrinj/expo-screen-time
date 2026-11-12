import * as path from 'path';

import { applyTargetToXcodeProject } from '../withTargetXcodeProject';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const xcode = require('xcode');

// Vanilla Expo app pbxproj (1 app target, no extensions) captured from git history.
const FIXTURE = path.join(__dirname, 'fixtures', 'project.pbxproj');
const PROJECT_ROOT = '/app/ios';
const TARGET = 'ShieldConfigurationExtension';

// SharedDefaults.swift is a basename every target shares, which can orphan a
// build file — keep it in the list so the "every source has a parent group" check
// actually guards that case. The .entitlements is deliberately included:
// the implementation must filter it out (wiring it would mint an orphan
// PBXBuildFile) — the no-orphan test below asserts that defense.
const FILE_URIS = [
  `${PROJECT_ROOT}/${TARGET}/${TARGET}.swift`,
  `${PROJECT_ROOT}/${TARGET}/SharedDefaults.swift`,
  `${PROJECT_ROOT}/${TARGET}/${TARGET}.entitlements`,
];

function load() {
  const project = xcode.project(FIXTURE);
  project.parseSync();
  return project;
}

function apply(project: any) {
  applyTargetToXcodeProject(project, {
    targetName: TARGET,
    bundleIdentifier: 'com.example.app.shieldconfig',
    appleTeamId: 'ABCDE12345',
    platformProjectRoot: PROJECT_ROOT,
    fileUris: FILE_URIS,
    marketingVersion: '1.0',
    currentProjectVersion: '1',
  });
}

const objects = (project: any) => project.hash.project.objects as Record<string, Record<string, any>>;
const realKeys = (section: Record<string, any> | undefined) =>
  section ? Object.keys(section).filter((k) => !k.endsWith('_comment')) : [];
const realValues = (section: Record<string, any> | undefined) =>
  realKeys(section).map((k) => section![k]);

const serialize = (project: any): string => project.writeSync();

describe('applyTargetToXcodeProject', () => {
  it('adds exactly one app-extension target named after the target', () => {
    const project = load();
    apply(project);

    const extTargets = realValues(objects(project).PBXNativeTarget).filter(
      (t) => typeof t === 'object' && String(t.productType).includes('app-extension')
    );
    expect(extTargets).toHaveLength(1);
    expect(extTargets[0].name).toBe(TARGET);
  });

  it('is idempotent — re-applying leaves the pbxproj byte-identical', () => {
    const project = load();
    apply(project);
    const before = serialize(project);
    apply(project);
    expect(serialize(project)).toBe(before);
  });

  it('gives every source build file a parent group (no orphans from basename dedup)', () => {
    const project = load();
    apply(project);
    const objs = objects(project);

    // Our target's Sources phase.
    const target = realValues(objs.PBXNativeTarget).find((t) => typeof t === 'object' && t.name === TARGET);
    const sourcesPhaseRef = target.buildPhases.find((p: any) => objs.PBXSourcesBuildPhase?.[p.value]);
    expect(sourcesPhaseRef).toBeDefined();
    const sourcesPhase = objs.PBXSourcesBuildPhase[sourcesPhaseRef.value];

    // fileRefs that belong to some group.
    const grouped = new Set<string>();
    for (const g of realValues(objs.PBXGroup)) {
      for (const child of g.children ?? []) grouped.add(child.value);
    }

    expect(sourcesPhase.files.length).toBeGreaterThan(0);
    for (const f of sourcesPhase.files) {
      const fileRef = objs.PBXBuildFile[f.value].fileRef;
      expect(grouped.has(fileRef)).toBe(true);
    }
  });

  it('creates a virtual target group with no `path` key', () => {
    const project = load();
    apply(project);

    const group = realValues(objects(project).PBXGroup).find(
      (g) => typeof g === 'object' && g.name === TARGET
    );
    expect(group).toBeDefined();
    expect(group.path).toBeUndefined();
  });

  it('never wires the .entitlements into the pbxproj (no orphan build file)', () => {
    const project = load();
    apply(project);
    const objs = objects(project);

    // No PBXBuildFile may point at an .entitlements file reference…
    const entitlementsRefs = new Set(
      realKeys(objs.PBXFileReference).filter((k) =>
        String(objs.PBXFileReference[k]?.path ?? '').includes('.entitlements')
      )
    );
    for (const k of realKeys(objs.PBXBuildFile)) {
      expect(entitlementsRefs.has(objs.PBXBuildFile[k].fileRef)).toBe(false);
    }
    // …and the target group must not contain it either.
    const group = realValues(objs.PBXGroup).find((g) => typeof g === 'object' && g.name === TARGET);
    for (const child of group.children ?? []) {
      expect(entitlementsRefs.has(child.value)).toBe(false);
    }
  });
});

describe('applyTargetToXcodeProject — all four targets', () => {
  const ALL_TARGETS = [
    'ShieldConfigurationExtension',
    'ShieldActionExtension',
    'DeviceActivityMonitorExtension',
    'DeviceActivityReportExtension',
  ];

  function applyAll(project: any) {
    for (const name of ALL_TARGETS) {
      applyTargetToXcodeProject(project, {
        targetName: name,
        bundleIdentifier: `com.example.app.${name.toLowerCase()}`,
        appleTeamId: 'ABCDE12345',
        platformProjectRoot: PROJECT_ROOT,
        fileUris: [
          `${PROJECT_ROOT}/${name}/${name}.swift`,
          // The shared basename across every target — the historical orphaning trigger.
          `${PROJECT_ROOT}/${name}/SharedDefaults.swift`,
        ],
        marketingVersion: '1.0',
        currentProjectVersion: '1',
      });
    }
  }

  it('is idempotent across all four targets and embeds all four .appex once', () => {
    const project = load();
    applyAll(project);
    const before = serialize(project);
    applyAll(project);
    expect(serialize(project)).toBe(before);

    const objs = objects(project);
    // Exactly 4 app-extension targets…
    const extTargets = realValues(objs.PBXNativeTarget).filter(
      (t) => typeof t === 'object' && String(t.productType).includes('app-extension')
    );
    expect(extTargets.map((t: any) => t.name).sort()).toEqual([...ALL_TARGETS].sort());
    // …one shared "Embed Foundation Extensions" copy phase with the 4 .appex…
    const copyPhases = realValues(objs.PBXCopyFilesBuildPhase);
    expect(copyPhases).toHaveLength(1);
    expect(copyPhases[0].files).toHaveLength(4);
    // …and 4 host-target dependencies.
    expect(realKeys(objs.PBXTargetDependency)).toHaveLength(4);
  });
});
