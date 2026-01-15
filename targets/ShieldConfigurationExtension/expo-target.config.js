const shouldIncludeTarget = require('../shared/shouldIncludeTarget');

/** @type {import('@bacons/apple-targets').ConfigFunction} */
module.exports = (config) => {
  if (!shouldIncludeTarget(config, 'ShieldConfigurationExtension')) {
    return null;
  }

  return {
    type: "shield-config",
    name: "ShieldConfigurationExtension",
    deploymentTarget: "16.0",
    entitlements: {
      "com.apple.developer.family-controls": true,
    },
  };
};
