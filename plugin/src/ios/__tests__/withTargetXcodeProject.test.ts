import * as path from 'path';
// eslint-disable-next-line @typescript-eslint/no-var-requires
const xcode = require('xcode');

import { applyTargetToXcodeProject } from '../withTargetXcodeProject';

// Vanilla Expo app pbxproj (1 app target, no extensions) captured from git history.
const FIXTURE = path.join(__dirname, 'fixtures', 'project.pbxproj');
const PROJECT_ROOT = '/app/ios';
const TARGET = 'ShieldConfigurationExtension';

// SharedDefaults.swift is the colliding basename that previously orphaned a
// build file — keep it in the list so the "every source has a parent group" check
// actually guards that regression.
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

const SECTIONS = [
  'PBXNativeTarget',
  'PBXGroup',
  'PBXBuildFile',
  'PBXFileReference',
  'PBXSourcesBuildPhase',
  'PBXCopyFilesBuildPhase',
  'PBXFrameworksBuildPhase',
  'XCBuildConfiguration',
  'XCConfigurationList',
  'PBXTargetDependency',
  'PBXContainerItemProxy',
];

const counts = (project: any) =>
  Object.fromEntries(SECTIONS.map((s) => [s, realKeys(objects(project)[s]).length]));

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

  it('is idempotent — re-applying changes no object counts', () => {
    const project = load();
    apply(project);
    const before = counts(project);
    apply(project);
    expect(counts(project)).toEqual(before);
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
});
