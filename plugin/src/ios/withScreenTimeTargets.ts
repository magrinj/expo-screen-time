import { ConfigPlugin, StaticPlugin, withPlugins } from 'expo/config-plugins';

import { SCREEN_TIME_TARGETS, ScreenTimeTarget } from './targets';
import withScreenTimeEasConfig from './withScreenTimeEasConfig';
import withTargetSourceFiles from './withTargetSourceFiles';
import withTargetXcodeProject from './withTargetXcodeProject';

type ScreenTimeTargetsProps = {
  /** The package's `targets/` directory (resolved from the plugin's location). */
  targetsRoot: string;
  /** Shared App Group identifier, already resolved by the entry plugin. */
  groupIdentifier: string;
};

/**
 * Generates the Screen Time app-extension targets directly via Expo's Xcode
 * config-plugin mods.
 *
 * For each enabled target it writes the source files (withDangerousMod), wires
 * the Xcode project (withXcodeProject), and registers EAS signing. Expo runs
 * dangerous mods before the xcodeProject mod, so the per-target `getFileUris`
 * closure is populated (by `onFilesGenerated`) by the time the project is edited.
 */
const withScreenTimeTargets: ConfigPlugin<ScreenTimeTargetsProps> = (
  config,
  { targetsRoot, groupIdentifier }
) => {
  const bundleIdentifier = config.ios?.bundleIdentifier;
  if (!bundleIdentifier) {
    // Without a bundle id we can't derive extension bundle ids; the entry
    // plugin still applies the main-app entitlements.
    return config;
  }

  const targets = filterEnabledTargets(config);
  const plugins: (StaticPlugin | ConfigPlugin)[] = [];

  for (const target of targets) {
    const targetBundleId = `${bundleIdentifier}.${target.slug}`;

    // Per-target closure threading the generated file list from the
    // (earlier-running) source mod to the (later-running) pbxproj mod.
    let files: string[] = [];

    plugins.push(
      [
        withTargetSourceFiles,
        {
          targetName: target.name,
          targetsRoot,
          groupIdentifier,
          onFilesGenerated: (generated: string[]) => {
            files = generated;
          },
        },
      ],
      [
        withTargetXcodeProject,
        {
          targetName: target.name,
          bundleIdentifier: targetBundleId,
          appleTeamId: config.ios?.appleTeamId,
          getFileUris: () => files,
        },
      ],
      [
        withScreenTimeEasConfig,
        { targetName: target.name, bundleIdentifier: targetBundleId, groupIdentifier },
      ]
    );
  }

  return withPlugins(config, plugins);
};

/**
 * Opt-in/out via `expo.extra.expoScreenTime.targets`: an array of target names
 * to include. Omitted → all targets; empty array → none.
 */
function filterEnabledTargets(config: { extra?: any }): ScreenTimeTarget[] {
  const enabled = config.extra?.expoScreenTime?.targets;
  if (!Array.isArray(enabled)) {
    return SCREEN_TIME_TARGETS;
  }
  const known = SCREEN_TIME_TARGETS.map((t) => t.name);
  const unknown = enabled.filter((name) => !known.includes(name));
  if (unknown.length) {
    console.warn(
      `[expo-screen-time] Ignoring unknown target name(s) in extra.expoScreenTime.targets: ` +
        `${unknown.join(', ')}. Valid names: ${known.join(', ')}.`
    );
  }
  return SCREEN_TIME_TARGETS.filter((target) => enabled.includes(target.name));
}

export default withScreenTimeTargets;
