/**
 * Tag-lock reference app: tapping a physical tag toggles a lock —
 * every app except the chosen essentials is shielded, and removing the app
 * gets harder. Shared flow — no platform checks.
 */
import {
  ActivityPicker,
  allowAppRemoval,
  blockSelection,
  clearShieldActions,
  clearShieldConfig,
  denyAppRemoval,
  getCapabilities,
  getEnforcementGaps,
  getScreenTimePermissionsAsync,
  hasSelection,
  isBlocked,
  setShieldActions,
  setShieldConfig,
  unblockAll,
  type Enforcement,
  type EnforcementGap,
  type ScreenTimeFeature,
} from "expo-screen-time";
import { useEffect, useState } from "react";
import { Alert, Button, Text, View } from "react-native";

import { PermissionGate } from "./shared/PermissionGate";

const ESSENTIALS = "essentials";
const EMERGENCY_UNLOCKS = 5;
const FEATURES: ScreenTimeFeature[] = ["blocking", "appRemovalProtection"];

/** What "harder to remove" means on this device — from data, not platform. */
const REMOVAL_COPY: Record<Enforcement, string> = {
  enforced: "This app can't be deleted while you're locked.",
  friction:
    "Deleting this app takes extra, deliberate steps while you're locked.",
  none: "This app can still be deleted while you're locked.",
};

async function lock(): Promise<boolean> {
  if (!(await hasSelection(ESSENTIALS))) {
    Alert.alert("Pick your essentials first");
    return false;
  }
  // Access can be revoked from Settings at any time (Android: Usage access,
  // Accessibility) — check right before relying on it.
  if (!(await getScreenTimePermissionsAsync({ features: FEATURES })).granted) {
    Alert.alert("This app lost its permissions", "Reopen it to restore them.");
    return false;
  }
  setShieldConfig({
    title: { text: "Locked" },
    subtitle: { text: "Tap your tag to get this app back." },
    icon: "lock.fill",
    backgroundColor: "#0A0A0A",
    primaryButton: { text: "OK" },
  });
  // A single button that only closes: no way out from the shield itself.
  setShieldActions({ primaryAction: "close" });
  blockSelection(ESSENTIALS, { mode: "allowlist" });
  if (getCapabilities().appRemovalProtection !== "none") denyAppRemoval();
  return true;
}

function release() {
  unblockAll();
  allowAppRemoval();
  // Back to the library's default shield for any other block.
  clearShieldConfig();
  clearShieldActions();
}

/** Honest about what "locked" means here — enforced by the OS, or friction. */
const BLOCKING_COPY: Record<Enforcement, string> = {
  enforced: "Blocked apps stay shut until you tap your tag.",
  friction:
    "Blocked apps stay shut until you tap your tag. Stopping this app from Settings is logged below.",
  none: "This device can't block apps.",
};

function TagLockHome() {
  const [locked, setLocked] = useState(isBlocked);
  const [emergencyLeft, setEmergencyLeft] = useState(EMERGENCY_UNLOCKS);
  const [gaps, setGaps] = useState<EnforcementGap[]>([]);
  const { appRemovalProtection, blocking } = getCapabilities();

  // While locked, show any period when the block was not enforced (e.g.
  // the app was force-stopped) — tamper evidence instead of a false promise.
  useEffect(() => {
    if (locked) getEnforcementGaps().then(setGaps);
  }, [locked]);

  // Wire this to your NFC reader; the tag itself is outside this library.
  const onTagScanned = async () => {
    if (locked) {
      release();
      setLocked(false);
    } else {
      setLocked(await lock());
    }
  };

  return (
    <View style={{ flex: 1, padding: 20, gap: 12 }}>
      <Text style={{ fontSize: 28, fontWeight: "800" }}>
        {locked ? "Locked" : "Unlocked"}
      </Text>
      <Text>{BLOCKING_COPY[blocking]}</Text>
      <Text>{REMOVAL_COPY[appRemovalProtection]}</Text>
      {gaps.map((gap) => (
        <Text key={gap.from} style={{ color: "#B45309" }}>
          Unprotected from {new Date(gap.from).toLocaleString()} ({gap.reason})
        </Text>
      ))}
      {!locked && (
        <ActivityPicker
          selectionId={ESSENTIALS}
          headerText="Apps you keep while locked"
        />
      )}
      <Button title="Tap your tag" onPress={onTagScanned} />
      {locked && (
        <Button
          title={`Emergency unlock (${emergencyLeft} left)`}
          disabled={emergencyLeft === 0}
          onPress={() => {
            release();
            setLocked(false);
            setEmergencyLeft((left) => left - 1);
          }}
        />
      )}
    </View>
  );
}

export default function TagLockApp() {
  return (
    <PermissionGate features={FEATURES}>
      <TagLockHome />
    </PermissionGate>
  );
}
