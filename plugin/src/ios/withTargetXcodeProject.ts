import { ConfigPlugin, XcodeProject, withXcodeProject } from 'expo/config-plugins';
import * as path from 'path';

import { EXTENSION_DEPLOYMENT_TARGET } from './targets';

const EMBED_GROUP = 'Embed Foundation Extensions';

type TargetXcodeProjectProps = {
  targetName: string;
  bundleIdentifier: string;
  appleTeamId?: string;
  /** Absolute paths of the files written into the target directory (Swift + entitlements). */
  getFileUris: () => string[];
};

/**
 * Adds one app-extension target to the host app's Xcode project, with no
 * dependency on @bacons/apple-targets. Drives the legacy `xcode` pbxProject
 * (already bundled by @expo/config-plugins) through Expo's `withXcodeProject`
 * mod — the exact recipe Expo's first-party `expo-widgets` uses.
 *
 * Every PBX object is guarded by an existence check so re-running prebuild is
 * idempotent (no duplicate targets / groups / phases).
 */
const withTargetXcodeProject: ConfigPlugin<TargetXcodeProjectProps> = (
  config,
  { targetName, bundleIdentifier, appleTeamId, getFileUris }
) =>
  withXcodeProject(config, (config) => {
    const project = config.modResults;
    const targetUuid = project.generateUuid();
    const marketingVersion = config.ios?.version ?? config.version ?? '1.0';
    const currentProjectVersion = config.ios?.buildNumber ?? '1';

    const xCConfigurationList = addXCConfigurationList(project, {
      targetName,
      bundleIdentifier,
      appleTeamId,
      marketingVersion,
      currentProjectVersion,
    });

    const productFile = addProductFile(project, { targetName, groupName: EMBED_GROUP });

    const target = addToPbxNativeTargetSection(project, {
      targetName,
      targetUuid,
      productFile,
      xCConfigurationList,
    });

    addToPbxProjectSection(project, target);
    addTargetDependency(project, target);

    // Resolve generated file paths relative to the PROJECT root, not the target
    // dir, so shared file names (SharedDefaults.swift, SelectionStore.swift)
    // copied into several targets stay unique (`<Target>/SharedDefaults.swift`).
    // The `xcode` lib dedupes file references by path; colliding basenames leave
    // build files without a parent group and break `pod install`'s project save.
    const relativePaths = getFileUris().map((file) =>
      path.relative(config.modRequest.platformProjectRoot, file)
    );
    const swiftFiles = relativePaths.filter((file) => file.endsWith('.swift'));

    addBuildPhases(project, {
      targetUuid: target.uuid,
      groupName: EMBED_GROUP,
      productFile,
      sourceFiles: swiftFiles,
    });

    addPbxGroup(project, { targetName, files: relativePaths });

    return config;
  });

export default withTargetXcodeProject;

// ---------------------------------------------------------------------------
// pbxproj helpers (adapted from expo/expo packages/expo-widgets, MIT)
// ---------------------------------------------------------------------------

function addXCConfigurationList(
  project: XcodeProject,
  props: {
    targetName: string;
    bundleIdentifier: string;
    appleTeamId?: string;
    marketingVersion: string;
    currentProjectVersion: string;
  }
) {
  const existing = project.pbxTargetByName(props.targetName)?.buildConfigurationList;
  if (existing) {
    return {
      uuid: existing,
      xcConfigurationList: project.pbxXCConfigurationList()[existing],
    };
  }

  const common: Record<string, string> = {
    PRODUCT_NAME: `"$(TARGET_NAME)"`,
    PRODUCT_BUNDLE_IDENTIFIER: `"${props.bundleIdentifier}"`,
    INFOPLIST_FILE: `${props.targetName}/Info.plist`,
    CODE_SIGN_ENTITLEMENTS: `${props.targetName}/${props.targetName}.entitlements`,
    CODE_SIGN_STYLE: 'Automatic',
    SWIFT_VERSION: '5.0',
    TARGETED_DEVICE_FAMILY: `"1,2"`,
    IPHONEOS_DEPLOYMENT_TARGET: EXTENSION_DEPLOYMENT_TARGET,
    CURRENT_PROJECT_VERSION: `"${props.currentProjectVersion}"`,
    MARKETING_VERSION: `"${props.marketingVersion}"`,
    APPLICATION_EXTENSION_API_ONLY: 'YES',
    REGISTER_APP_GROUPS: 'YES',
    SKIP_INSTALL: 'YES',
    CLANG_ENABLE_MODULES: 'YES',
    // Disable SwiftUI Previews so __preview.dylib is not embedded in the .appex
    // (causes "AppexBundleContainsClassOrStoryboard" install failures on device).
    ENABLE_PREVIEWS: 'NO',
    ...(props.appleTeamId ? { DEVELOPMENT_TEAM: props.appleTeamId } : {}),
  };

  const perConfig: Record<'Debug' | 'Release', Record<string, string>> = {
    Debug: {
      SWIFT_OPTIMIZATION_LEVEL: `"-Onone"`,
      SWIFT_ACTIVE_COMPILATION_CONDITIONS: 'DEBUG',
      DEBUG_INFORMATION_FORMAT: 'dwarf',
    },
    Release: {
      SWIFT_OPTIMIZATION_LEVEL: `"-O"`,
      SWIFT_COMPILATION_MODE: 'wholemodule',
      DEBUG_INFORMATION_FORMAT: `"dwarf-with-dsym"`,
      ENABLE_NS_ASSERTIONS: 'NO',
    },
  };

  return project.addXCConfigurationList(
    (['Debug', 'Release'] as const).map((name) => ({
      name,
      isa: 'XCBuildConfiguration',
      buildSettings: { ...common, ...perConfig[name] },
    })),
    'Release',
    `Build configuration list for PBXNativeTarget "${props.targetName}"`
  );
}

function addProductFile(project: XcodeProject, { targetName, groupName }: { targetName: string; groupName: string }) {
  const basename = `${targetName}.appex`;
  const settings = { ATTRIBUTES: ['RemoveHeadersOnCopy'] };
  const section = project.pbxFileReferenceSection() as Record<string, any>;

  const fileRef = Object.keys(section).find((key) => {
    const ref = section[key];
    if (!ref || typeof ref === 'string') return false;
    return [basename, `"${basename}"`].includes(ref.path) || [basename, `"${basename}"`].includes(ref.name);
  });

  if (fileRef) {
    const buildFiles = project.pbxBuildFileSection();
    const uuid =
      Object.keys(buildFiles).find((key) => buildFiles[key]?.fileRef === fileRef) ?? project.generateUuid();
    return { uuid, fileRef, sourceTree: 'BUILT_PRODUCTS_DIR', target: targetName, basename, group: groupName, settings };
  }

  return project.addProductFile(targetName, {
    basename,
    group: groupName,
    explicitFileType: 'wrapper.app-extension',
    settings,
    includeInIndex: 0,
    path: basename,
    sourceTree: 'BUILT_PRODUCTS_DIR',
  });
}

function addToPbxNativeTargetSection(
  project: XcodeProject,
  {
    targetName,
    targetUuid,
    productFile,
    xCConfigurationList,
  }: {
    targetName: string;
    targetUuid: string;
    productFile: { fileRef: string };
    xCConfigurationList: { uuid: string };
  }
) {
  const existingUuid = project.findTargetKey(targetName);
  if (existingUuid) {
    return { uuid: existingUuid, pbxNativeTarget: project.pbxNativeTargetSection()[existingUuid] };
  }

  const target = {
    uuid: targetUuid,
    pbxNativeTarget: {
      isa: 'PBXNativeTarget',
      name: targetName,
      productName: targetName,
      productReference: productFile.fileRef,
      productType: `"com.apple.product-type.app-extension"`,
      buildConfigurationList: xCConfigurationList.uuid,
      buildPhases: [],
      buildRules: [],
      dependencies: [],
    },
  };

  project.addToPbxNativeTargetSection(target);
  return target;
}

function addToPbxProjectSection(project: XcodeProject, target: { uuid: string }) {
  const projectSection = project.pbxProjectSection();
  const pbxProject = projectSection[project.getFirstProject().uuid];
  if (pbxProject.targets.some((t: { value: string }) => t.value === target.uuid)) {
    return;
  }
  project.addToPbxProjectSection(target);
  pbxProject.attributes.TargetAttributes ??= {};
  pbxProject.attributes.TargetAttributes[target.uuid] = { LastSwiftMigration: 1250 };
}

function addTargetDependency(project: XcodeProject, target: { uuid: string }) {
  const objects = project.hash.project.objects as Record<string, Record<string, any>>;
  objects.PBXTargetDependency ??= {};
  objects.PBXContainerItemProxy ??= {};
  const targetDependencies = objects.PBXTargetDependency;

  const mainTargetUuid = getAppTargetUuid(project);
  const mainTarget = project.pbxNativeTargetSection()[mainTargetUuid];
  mainTarget.dependencies ??= [];

  const alreadyDependent = mainTarget.dependencies.some(
    (dep: { value: string }) => targetDependencies[dep.value]?.target === target.uuid
  );
  if (alreadyDependent) {
    return;
  }
  project.addTargetDependency(mainTargetUuid, [target.uuid]);
}

function addBuildPhases(
  project: XcodeProject,
  {
    targetUuid,
    groupName,
    productFile,
    sourceFiles,
  }: {
    targetUuid: string;
    groupName: string;
    productFile: { uuid: string; basename: string; group: string };
    sourceFiles: string[];
  }
) {
  const buildPath = `""`;
  const folderType = 'app_extension';
  const mainTargetUuid = getAppTargetUuid(project);

  // Sources phase on the extension target (our copied .swift files).
  if (!getBuildPhase(project, 'PBXSourcesBuildPhase', targetUuid)) {
    project.addBuildPhase([...sourceFiles], 'PBXSourcesBuildPhase', 'Sources', targetUuid, folderType, buildPath);
  }

  // "Embed Foundation Extensions" copy phase on the host app (dstSubfolderSpec=13).
  if (!getBuildPhase(project, 'PBXCopyFilesBuildPhase', mainTargetUuid, groupName)) {
    project.addBuildPhase([], 'PBXCopyFilesBuildPhase', groupName, mainTargetUuid, folderType, buildPath);
  }
  const copyPhase = getBuildPhase(project, 'PBXCopyFilesBuildPhase', mainTargetUuid, groupName);
  if (copyPhase && !copyPhase.files.some((f: { value: string }) => f.value === productFile.uuid)) {
    copyPhase.files.push({ value: productFile.uuid, comment: `${productFile.basename} in ${productFile.group}` });
  }
  if (!project.pbxBuildFileSection()[productFile.uuid]) {
    project.addToPbxBuildFileSection(productFile);
  }

  // Frameworks phase — left empty; the extensions only use system frameworks
  // (ManagedSettings/ManagedSettingsUI/DeviceActivity/SwiftUI) which Swift
  // auto-links via `import`.
  if (!getBuildPhase(project, 'PBXFrameworksBuildPhase', targetUuid)) {
    project.addBuildPhase([], 'PBXFrameworksBuildPhase', 'Frameworks', targetUuid, folderType, buildPath);
  }
}

function getBuildPhase(
  project: XcodeProject,
  type: 'PBXSourcesBuildPhase' | 'PBXCopyFilesBuildPhase' | 'PBXFrameworksBuildPhase',
  targetUuid: string,
  comment?: string
): any {
  const section = (project.hash.project.objects as Record<string, Record<string, unknown>>)[type];
  const target = project.pbxNativeTargetSection()[targetUuid];
  if (!section || !target?.buildPhases) {
    return null;
  }
  const phase = target.buildPhases.find(
    (p: { value: string; comment: string }) => (!comment || p.comment === comment) && section[p.value]
  );
  return phase ? section[phase.value] : null;
}

/** The host app target's UUID — resolved by product type, not array position. */
function getAppTargetUuid(project: XcodeProject): string {
  const appTarget = (project as any).getTarget('com.apple.product-type.application');
  return appTarget?.uuid ?? project.getFirstTarget().uuid;
}

function addPbxGroup(project: XcodeProject, { targetName, files }: { targetName: string; files: string[] }) {
  if (project.pbxGroupByName(targetName)) {
    return;
  }
  // Paths are project-root-relative, so the group must be a *virtual* group with
  // no `path` of its own (a real path would double-prefix to
  // `<Target>/<Target>/file.swift`). Only the (already-referenced) source files
  // go in the group — they reuse the build-phase file references by path, giving
  // every source build file a parent. The entitlements is intentionally omitted:
  // it's referenced via CODE_SIGN_ENTITLEMENTS, and passing it here would mint an
  // orphan PBXBuildFile (the xcode lib adds every new path to the build-file section).
  const { uuid } = project.addPbxGroup(Array.from(new Set(files)), targetName, undefined as unknown as string);
  const groups = project.hash.project.objects['PBXGroup'] as Record<string, any>;
  // `addPbxGroup` always writes a `path` key; with no path it serializes as the
  // literal `path = undefined`, which Xcode reads as an "undefined/" folder. Drop
  // it so the group contributes nothing to its children's resolved paths.
  if (uuid && groups[uuid]) {
    delete groups[uuid].path;
  }
  if (uuid) {
    // Attach to the project's main group deterministically (not by scanning for an
    // anonymous group, which could match more than one in a consumer's project).
    project.addToPbxGroup(uuid, project.getFirstProject().firstProject.mainGroup);
  }
}
