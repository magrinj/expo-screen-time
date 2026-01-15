const shouldIncludeTarget = require('../shared/shouldIncludeTarget');

/** @type {import('@bacons/apple-targets').ConfigFunction} */
module.exports = (config) => {
  if (!shouldIncludeTarget(config, 'DeviceActivityReportExtension')) {
    return null;
  }

  return {
    type: "device-activity-report",
    name: "DeviceActivityReportExtension",
    deploymentTarget: "16.0",
    entitlements: {
      "com.apple.developer.family-controls": true,
    },
  };
};
