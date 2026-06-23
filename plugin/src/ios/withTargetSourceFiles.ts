import { ConfigPlugin, withDangerousMod } from 'expo/config-plugins';
import * as fs from 'fs';
import * as path from 'path';

type TargetSourceFilesProps = {
  targetName: string;
  /** The package's `targets/` directory that ships the Swift sources + Info.plist. */
  targetsRoot: string;
  groupIdentifier: string;
  /** Reports the absolute paths of the Swift files written into the target dir. */
  onFilesGenerated: (files: string[]) => void;
};

/**
 * Writes one extension target's files into the host app's iOS project:
 * copies the checked-in Swift sources + Info.plist from the package's
 * `targets/<name>/` folder and generates the `.entitlements` (family-controls +
 * the shared app group) so the extension can access the App Group container.
 */
const withTargetSourceFiles: ConfigPlugin<TargetSourceFilesProps> = (
  config,
  { targetName, targetsRoot, groupIdentifier, onFilesGenerated }
) =>
  withDangerousMod(config, [
    'ios',
    async (config) => {
      const sourceDir = path.join(targetsRoot, targetName);
      if (!fs.existsSync(sourceDir)) {
        throw new Error(
          `[expo-screen-time] Missing target sources for "${targetName}" at ${sourceDir}.`
        );
      }

      const targetDir = path.join(config.modRequest.platformProjectRoot, targetName);
      fs.rmSync(targetDir, { recursive: true, force: true });
      fs.mkdirSync(targetDir, { recursive: true });

      // Copy every Swift source from the package into the generated target dir.
      const swiftFiles = fs
        .readdirSync(sourceDir)
        .filter((file) => file.endsWith('.swift'))
        .map((file) => {
          const dest = path.join(targetDir, file);
          fs.copyFileSync(path.join(sourceDir, file), dest);
          return dest;
        });

      // Copy the checked-in Info.plist verbatim (it carries the NSExtension block).
      fs.copyFileSync(path.join(sourceDir, 'Info.plist'), path.join(targetDir, 'Info.plist'));

      // Generate the entitlements (family-controls + shared app group).
      fs.writeFileSync(
        path.join(targetDir, `${targetName}.entitlements`),
        entitlementsPlist(groupIdentifier)
      );

      onFilesGenerated(swiftFiles);
      return config;
    },
  ]);

const entitlementsPlist = (groupIdentifier: string) => `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>com.apple.developer.family-controls</key>
	<true/>
	<key>com.apple.security.application-groups</key>
	<array>
		<string>${groupIdentifier}</string>
	</array>
</dict>
</plist>
`;

export default withTargetSourceFiles;
