#!/usr/bin/env node

/**
 * Post-prebuild script to fix DeviceActivityReportExtension configuration.
 *
 * Fixes:
 * 1. Disables SwiftUI Previews to prevent __preview.dylib
 * 2. Removes NSExtensionPrincipalClass from Info.plist (not allowed for device-activity-report extensions)
 *
 * Usage: node scripts/disable-extension-previews.js
 */

const xcode = require("xcode");
const plist = require("@expo/plist");
const path = require("path");
const fs = require("fs");

const projectRoot = process.cwd();
const xcodeProjectPath = path.join(
  projectRoot,
  "ios",
  "exposcreentimeexample.xcodeproj",
  "project.pbxproj"
);

const extensionInfoPlistPath = path.join(
  projectRoot,
  "ios",
  "exposcreentimeexample",
  "DeviceActivityReportExtension",
  "Info.plist"
);

console.log("=== Fixing DeviceActivityReportExtension ===\n");

// Step 1: Fix Xcode project build settings
console.log(`[1] Reading Xcode project: ${xcodeProjectPath}`);

if (!fs.existsSync(xcodeProjectPath)) {
  console.error("Project file not found. Run 'npx expo prebuild' first.");
  process.exit(1);
}

const project = xcode.project(xcodeProjectPath);
project.parseSync();

const targets = project.pbxNativeTargetSection();
let xcodeModified = false;

for (const key in targets) {
  const target = targets[key];
  if (typeof target !== "object" || !target.name) continue;

  if (target.name === "DeviceActivityReportExtension") {
    console.log(`    Found extension target: ${target.name}`);

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
          xcodeModified = true;
          console.log(`    Disabled previews for config: ${buildConfig.name}`);
        }
      }
    }
  }
}

if (xcodeModified) {
  fs.writeFileSync(xcodeProjectPath, project.writeSync());
  console.log("    Xcode project updated.\n");
}

// Step 2: Fix Info.plist - remove NSExtensionPrincipalClass
console.log(`[2] Fixing Info.plist: ${extensionInfoPlistPath}`);

if (!fs.existsSync(extensionInfoPlistPath)) {
  console.log("    Info.plist not found, skipping.\n");
} else {
  const infoPlistContent = fs.readFileSync(extensionInfoPlistPath, "utf8");
  const infoPlist = plist.parse(infoPlistContent);

  if (infoPlist.NSExtension && infoPlist.NSExtension.NSExtensionPrincipalClass) {
    console.log(
      `    Removing NSExtensionPrincipalClass: ${infoPlist.NSExtension.NSExtensionPrincipalClass}`
    );
    delete infoPlist.NSExtension.NSExtensionPrincipalClass;

    const updatedContent = plist.build(infoPlist);
    fs.writeFileSync(extensionInfoPlistPath, updatedContent);
    console.log("    Info.plist updated.\n");
  } else {
    console.log("    NSExtensionPrincipalClass not found, no changes needed.\n");
  }
}

console.log("=== Done ===");
