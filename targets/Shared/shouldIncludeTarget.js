/**
 * Check if a target should be included based on expo.extra.expoScreenTime.targets
 * @param {object} config - Expo config passed to the config function
 * @param {string} targetName - Name of this target
 * @returns {boolean}
 */
module.exports = function shouldIncludeTarget(config, targetName) {
  const screenTimeConfig = config?.extra?.expoScreenTime;
  const enabledTargets = screenTimeConfig?.targets;

  // If no targets array specified, include all targets (default behavior)
  if (!Array.isArray(enabledTargets)) {
    return true;
  }

  // If empty array, include no targets
  if (enabledTargets.length === 0) {
    return false;
  }

  // Check if this target is in the list
  return enabledTargets.includes(targetName);
};
