const shouldIncludeTarget = require('../shared/shouldIncludeTarget');

/** @type {import('@bacons/apple-targets').ConfigFunction} */
module.exports = (config) => {
  if (!shouldIncludeTarget(config, 'DeviceActivityMonitorExtension')) {
    return null;
  }

  return {
    type: "device-activity-monitor",
    name: "DeviceActivityMonitorExtension",
    deploymentTarget: "16.0",
    entitlements: {
      "com.apple.developer.family-controls": true,
    },
  };
};
