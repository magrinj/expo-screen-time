const { withDangerousMod } = require("expo/config-plugins");
const xcode = require("xcode");
const path = require("path");
const fs = require("fs");

/**
 * Config plugin to disable SwiftUI Previews for app extensions.
 * This prevents __preview.dylib from being included in extension bundles,
 * which causes "AppexBundleContainsClassOrStoryboard" errors on device install.
 */
module.exports = function disableExtensionPreviews(config) {
  return withDangerousMod(config, [
    "ios",
    async (config) => {
      const projectRoot = config.modRequest.projectRoot;
      const projectName = config.modRequest.projectName;
      const xcodeProjectPath = path.join(
        projectRoot,
        "ios",
        `${projectName}.xcodeproj`,
        "project.pbxproj"
      );

      console.log(
        `[disable-extension-previews] Reading project: ${xcodeProjectPath}`
      );

      if (!fs.existsSync(xcodeProjectPath)) {
        console.log("[disable-extension-previews] Project file not found");
        return config;
      }

      const project = xcode.project(xcodeProjectPath);
      project.parseSync();

      // Get all targets
      const targets = project.pbxNativeTargetSection();
      const targetNames = Object.values(targets)
        .filter((t) => typeof t === "object" && t.name)
        .map((t) => t.name);

      console.log("[disable-extension-previews] Found targets:", targetNames);

      let modified = false;

      for (const key in targets) {
        const target = targets[key];
        if (typeof target !== "object" || !target.name) continue;

        // Check if this is the DeviceActivityReportExtension
        if (target.name === "DeviceActivityReportExtension") {
          console.log(
            `[disable-extension-previews] Found extension target: ${target.name}`
          );

          // Get the build configuration list for this target
          const configListId = target.buildConfigurationList;
          const configLists = project.pbxXCConfigurationList();
          const configList = configLists[configListId];

          if (configList && configList.buildConfigurations) {
            const buildConfigs = project.pbxXCBuildConfigurationSection();

            for (const configRef of configList.buildConfigurations) {
              const configId = configRef.value;
              const buildConfig = buildConfigs[configId];

              if (buildConfig && buildConfig.buildSettings) {
                buildConfig.buildSettings.ENABLE_PREVIEWS = "NO";
                modified = true;
                console.log(
                  `[disable-extension-previews] Disabled previews for config: ${buildConfig.name}`
                );
              }
            }
          }
        }
      }

      if (modified) {
        fs.writeFileSync(xcodeProjectPath, project.writeSync());
        console.log("[disable-extension-previews] Project file updated");
      }

      return config;
    },
  ]);
};
