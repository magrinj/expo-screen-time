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
export const getCapabilities: Module["getCapabilities"] = forward("getCapabilities");
export const getEnforcementGaps: Module["getEnforcementGaps"] = forward("getEnforcementGaps");
export const openApplication: Module["openApplication"] = forward("openApplication");

// Permissions
export const requestScreenTimePermissionsAsync: Module["requestScreenTimePermissionsAsync"] = forward("requestScreenTimePermissionsAsync");
export const getScreenTimePermissionsAsync: Module["getScreenTimePermissionsAsync"] = forward("getScreenTimePermissionsAsync");

// App control
export const denyAppRemoval: Module["denyAppRemoval"] = forward("denyAppRemoval");
export const allowAppRemoval: Module["allowAppRemoval"] = forward("allowAppRemoval");
export const denyAppInstallation: Module["denyAppInstallation"] = forward("denyAppInstallation");
export const allowAppInstallation: Module["allowAppInstallation"] = forward("allowAppInstallation");

// Blocking
export const blockSelection: Module["blockSelection"] = forward("blockSelection");
export const unblockAll: Module["unblockAll"] = forward("unblockAll");
export const isBlocked: Module["isBlocked"] = forward("isBlocked");
export const blockWebDomains: Module["blockWebDomains"] = forward("blockWebDomains");
export const unblockWebDomains: Module["unblockWebDomains"] = forward("unblockWebDomains");
export const unblockAllWebDomains: Module["unblockAllWebDomains"] = forward("unblockAllWebDomains");
export const getBlockedWebDomains: Module["getBlockedWebDomains"] = forward("getBlockedWebDomains");

// Temporary unlock
export const temporaryUnlock: Module["temporaryUnlock"] = forward("temporaryUnlock");
export const isTemporarilyUnlocked: Module["isTemporarilyUnlocked"] = forward("isTemporarilyUnlocked");
export const getRemainingUnlockSeconds: Module["getRemainingUnlockSeconds"] = forward("getRemainingUnlockSeconds");
export const relock: Module["relock"] = forward("relock");

// Shields
export const setShieldConfig: Module["setShieldConfig"] = forward("setShieldConfig");
export const setShieldConfigForActivity: Module["setShieldConfigForActivity"] = forward("setShieldConfigForActivity");
export const clearShieldConfig: Module["clearShieldConfig"] = forward("clearShieldConfig");
export const setShieldActions: Module["setShieldActions"] = forward("setShieldActions");
export const setShieldActionsForActivity: Module["setShieldActionsForActivity"] = forward("setShieldActionsForActivity");
export const clearShieldActions: Module["clearShieldActions"] = forward("clearShieldActions");
export const drainShieldInteractions: Module["drainShieldInteractions"] = forward("drainShieldInteractions");

// Monitoring
export const startMonitoring: Module["startMonitoring"] = forward("startMonitoring");
export const stopMonitoring: Module["stopMonitoring"] = forward("stopMonitoring");
export const stopAllMonitoring: Module["stopAllMonitoring"] = forward("stopAllMonitoring");
export const getMonitoredActivities: Module["getMonitoredActivities"] = forward("getMonitoredActivities");
export const getMonitorEvents: Module["getMonitorEvents"] = forward("getMonitorEvents");
export const clearMonitorEvents: Module["clearMonitorEvents"] = forward("clearMonitorEvents");

// Platform-specific
export const getAppGroup: Module["getAppGroup"] = forward("getAppGroup");
export const debugShieldConfig: Module["debugShieldConfig"] = forward("debugShieldConfig");
