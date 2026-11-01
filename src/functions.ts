import ExpoScreenTimeModule from "./ExpoScreenTimeModule";

type Module = typeof ExpoScreenTimeModule;
type Method = (...args: never[]) => unknown;
type MethodName = {
  [K in keyof Module]-?: Module[K] extends Method ? K : never;
}[keyof Module] &
  keyof Module;

/**
 * A named export for a method of the default export. It looks the method up
 * at call time and calls it on the module, so importing never touches the
 * native module (a platform without it fails at the call, with its error).
 */
function forward<K extends MethodName>(name: K): Module[K] {
  return ((...args: unknown[]) =>
    (ExpoScreenTimeModule[name] as (...a: unknown[]) => unknown).apply(
      ExpoScreenTimeModule,
      args,
    )) as Module[K];
}

// Every function documented on the default export is also a named export.
// Selection utilities live in ./utils; report templates in ./index.

// Capabilities
export const getCapabilities = forward("getCapabilities");
export const getEnforcementGaps = forward("getEnforcementGaps");
export const openApplication = forward("openApplication");

// Permissions
export const requestScreenTimePermissionsAsync = forward("requestScreenTimePermissionsAsync");
export const getScreenTimePermissionsAsync = forward("getScreenTimePermissionsAsync");

// App control
export const denyAppRemoval = forward("denyAppRemoval");
export const allowAppRemoval = forward("allowAppRemoval");
export const denyAppInstallation = forward("denyAppInstallation");
export const allowAppInstallation = forward("allowAppInstallation");

// Blocking
export const blockSelection = forward("blockSelection");
export const unblockAll = forward("unblockAll");
export const isBlocked = forward("isBlocked");
export const blockWebDomains = forward("blockWebDomains");
export const unblockWebDomains = forward("unblockWebDomains");
export const unblockAllWebDomains = forward("unblockAllWebDomains");
export const getBlockedWebDomains = forward("getBlockedWebDomains");

// Temporary unlock
export const temporaryUnlock = forward("temporaryUnlock");
export const isTemporarilyUnlocked = forward("isTemporarilyUnlocked");
export const getRemainingUnlockSeconds = forward("getRemainingUnlockSeconds");
export const relock = forward("relock");

// Shields
export const setShieldConfig = forward("setShieldConfig");
export const setShieldConfigForActivity = forward("setShieldConfigForActivity");
export const clearShieldConfig = forward("clearShieldConfig");
export const setShieldActions = forward("setShieldActions");
export const setShieldActionsForActivity = forward("setShieldActionsForActivity");
export const clearShieldActions = forward("clearShieldActions");
export const drainShieldInteractions = forward("drainShieldInteractions");

// Monitoring
export const startMonitoring = forward("startMonitoring");
export const stopMonitoring = forward("stopMonitoring");
export const stopAllMonitoring = forward("stopAllMonitoring");
export const getMonitoredActivities = forward("getMonitoredActivities");
export const getMonitorEvents = forward("getMonitorEvents");
export const clearMonitorEvents = forward("clearMonitorEvents");

// Platform-specific
export const getAppGroup = forward("getAppGroup");
export const debugShieldConfig = forward("debugShieldConfig");
