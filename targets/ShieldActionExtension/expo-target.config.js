const shouldIncludeTarget = require('../shared/shouldIncludeTarget');

/** @type {import('@bacons/apple-targets').ConfigFunction} */
module.exports = (config) => {
  if (!shouldIncludeTarget(config, 'ShieldActionExtension')) {
    return null;
  }

  return {
    type: "shield-action",
    name: "ShieldActionExtension",
    deploymentTarget: "16.0",
    entitlements: {
      "com.apple.developer.family-controls": true,
    },
  };
};
