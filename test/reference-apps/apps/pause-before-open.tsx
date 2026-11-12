/**
 * Pause-before-open reference app: opening a "paused" app lands on a breathing
 * screen in this app first, then the user decides. Shared flow — no platform
 * checks.
 */
import {
  ActivityPicker,
  ApplicationLabel,
  blockSelection,
  drainShieldInteractions,
  getCapabilities,
  getSelectionMetadata,
  openApplication,
  setShieldActions,
  setShieldConfig,
  temporaryUnlock,
  type ShieldInteraction,
} from "expo-screen-time";
import { useEffect, useState } from "react";
import { AppState, Button, Text, View } from "react-native";

import { PermissionGate } from "./shared/PermissionGate";

const PAUSED_APPS = "paused-apps";
const BREATH_SECONDS = 8;
const UNLOCK_SECONDS = 3 * 60;

function configureShield() {
  // iOS cannot launch an app from the shield, so the library posts a
  // notification instead. It reports which, so the copy stays honest.
  const viaNotification = getCapabilities().shieldOpenApp === "notification";
  setShieldConfig({
    title: { text: "Take a breath" },
    subtitle: {
      text: viaNotification
        ? "Tap Continue, then the notification."
        : "Tap Continue to take a breath first.",
    },
    icon: "wind",
    primaryButton: { text: "Continue" },
    secondaryButton: { text: "Close app" },
  });
  setShieldActions({
    primaryAction: { type: "openApp" },
    secondaryAction: "close",
  });
}

/** The shield press that brought the user here, if it is still fresh. */
function takePendingIntercept(): ShieldInteraction | null {
  const latest = drainShieldInteractions()
    .filter((press) => press.button === "primary")
    .at(-1);
  if (!latest || Date.now() - latest.timestamp > 2 * 60_000) return null;
  return latest;
}

function Breathe({
  intercept,
  onDone,
}: {
  intercept: ShieldInteraction;
  onDone: () => void;
}) {
  const [secondsLeft, setSecondsLeft] = useState(BREATH_SECONDS);
  const [unlocked, setUnlocked] = useState(false);
  const app =
    intercept.target?.type === "application" ? intercept.target : null;

  useEffect(() => {
    if (secondsLeft === 0) return;
    const timer = setTimeout(() => setSecondsLeft((left) => left - 1), 1000);
    return () => clearTimeout(timer);
  }, [secondsLeft]);

  if (unlocked) {
    return (
      <View style={{ flex: 1, justifyContent: "center", gap: 12, padding: 24 }}>
        <Text style={{ fontSize: 22 }}>You have 3 minutes. Go back to</Text>
        {app && <ApplicationLabel handle={app.handle} />}
        <Button title="Done" onPress={onDone} />
      </View>
    );
  }

  return (
    <View
      style={{
        flex: 1,
        alignItems: "center",
        justifyContent: "center",
        gap: 16,
      }}
    >
      {app && (
        <ApplicationLabel
          handle={app.handle}
          displayMode="icon-only"
          style={{ width: 64, height: 64 }}
        />
      )}
      <Text style={{ fontSize: 28 }}>
        {secondsLeft > 0 ? `Breathe… ${secondsLeft}` : "Still want to go in?"}
      </Text>
      {secondsLeft === 0 && (
        <>
          <Button
            title="Open it for 3 minutes"
            onPress={async () => {
              // Only this app opens up — the other paused apps stay paused.
              await temporaryUnlock(UNLOCK_SECONDS, {
                target: app ?? undefined,
              });
              // Where the OS allows it, go straight there; otherwise the
              // screen below tells the user where to go back to.
              if (app && getCapabilities().openApplication) {
                await openApplication(app.handle);
                onDone();
              } else {
                setUnlocked(true);
              }
            }}
          />
          <Button title="No, I'm good" onPress={onDone} />
        </>
      )}
    </View>
  );
}

function PauseHome() {
  const [intercept, setIntercept] = useState(takePendingIntercept);
  const [pausedCount, setPausedCount] = useState(0);

  useEffect(() => {
    configureShield();
    getSelectionMetadata(PAUSED_APPS).then((metadata) =>
      setPausedCount(metadata?.applicationCount ?? 0),
    );
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") setIntercept(takePendingIntercept());
    });
    return () => subscription.remove();
  }, []);

  if (intercept) {
    return <Breathe intercept={intercept} onDone={() => setIntercept(null)} />;
  }

  return (
    <View style={{ flex: 1, padding: 20, gap: 12 }}>
      <Text style={{ fontSize: 24, fontWeight: "700" }}>
        {pausedCount} apps get a pause
      </Text>
      <ActivityPicker
        selectionId={PAUSED_APPS}
        onSelectionChange={({ nativeEvent }) =>
          setPausedCount(nativeEvent.metadata.applicationCount)
        }
      />
      <Button
        title="Turn on pauses"
        onPress={() => blockSelection(PAUSED_APPS)}
      />
    </View>
  );
}

export default function PauseApp() {
  return (
    <PermissionGate features={["blocking", "shieldOpenApp"]}>
      <PauseHome />
    </PermissionGate>
  );
}
