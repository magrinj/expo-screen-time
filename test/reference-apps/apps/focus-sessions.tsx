/**
 * Focus-sessions reference app: focus sessions, recurring focus hours, earned
 * breaks, a branded shield and usage stats. Shared flow — no platform checks.
 */
import {
  ActivityPicker,
  DeviceActivityReport,
  Report,
  TokenLabel,
  drainShieldInteractions,
  getCapabilities,
  getRemainingUnlockSeconds,
  isBlocked,
  isScreenTimeError,
  isTemporarilyUnlocked,
  relock,
  setShieldActions,
  setShieldConfig,
  startMonitoring,
  stopMonitoring,
  temporaryUnlock,
  useMonitorEvents,
  type DateComponents,
  type ShieldInteraction,
} from "expo-screen-time";
import { useEffect, useState } from "react";
import { AppState, Button, ScrollView, Text, View } from "react-native";

import { PermissionGate } from "./shared/PermissionGate";

const DISTRACTIONS = "distractions";
const SESSION = "focus-session";
const FOCUS_HOURS = "focus-hours";

const clock = (date: Date): DateComponents => ({
  hour: date.getHours(),
  minute: date.getMinutes(),
  second: date.getSeconds(),
});

/** A one-off session starting now. The 15-minute floor is the same everywhere. */
async function startSession(minutes: number) {
  const { minIntervalSeconds } = getCapabilities().limits;
  const seconds = Math.max(minutes * 60, minIntervalSeconds);
  const now = new Date();
  await startMonitoring({
    activityName: SESSION,
    selectionId: DISTRACTIONS,
    schedule: {
      intervalStart: clock(now),
      intervalEnd: clock(new Date(now.getTime() + seconds * 1000)),
      repeats: false,
      warningTime: 60,
    },
  });
}

/** Weekdays 9:00–17:00, with a heads-up five minutes before the end. */
function startFocusHours() {
  return startMonitoring({
    activityName: FOCUS_HOURS,
    selectionId: DISTRACTIONS,
    weekdays: [2, 3, 4, 5, 6],
    schedule: {
      intervalStart: { hour: 9, minute: 0 },
      intervalEnd: { hour: 17, minute: 0 },
      repeats: true,
      warningTime: 5 * 60,
    },
  });
}

function brandShield() {
  setShieldConfig({
    title: { text: "Stay in the zone", color: "#FFFFFF" },
    subtitle: { text: "Paused for your focus session.", color: "#D1D5DB" },
    icon: "hourglass",
    backgroundColor: "#111827",
    backgroundBlurStyle: "systemUltraThinMaterialDark",
    primaryButton: {
      text: "Back to focus",
      color: "#111827",
      backgroundColor: "#FDE68A",
    },
    secondaryButton: { text: "5 more minutes", color: "#FDE68A" },
  });
  // "5 more minutes" of actual use, metered exactly like temporaryUnlock.
  setShieldActions({
    primaryAction: "close",
    secondaryAction: { type: "snooze", duration: 5 * 60 },
  });
}

/** Shield presses, collected whenever the app comes back to the foreground. */
function useBlockedAttempts() {
  const [attempts, setAttempts] = useState<ShieldInteraction[]>([]);
  useEffect(() => {
    const collect = () => {
      const fresh = drainShieldInteractions();
      if (fresh.length > 0) setAttempts((previous) => [...fresh, ...previous]);
    };
    collect();
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") collect();
    });
    return () => subscription.remove();
  }, []);
  return attempts;
}

/** Reading is a cheap sync call; the value itself moves in metering steps. */
const BREAK_REFRESH_MS = 5_000;

function BreakButton() {
  const [remaining, setRemaining] = useState(() =>
    isTemporarilyUnlocked() ? getRemainingUnlockSeconds() : 0,
  );
  // Seconds per metering step: 1 on Android, 30+ on iOS — so the copy only
  // promises the precision this device actually has.
  const [step, setStep] = useState(60);
  const onBreak = remaining > 0;

  useEffect(() => {
    if (!onBreak) return;
    const timer = setInterval(
      () => setRemaining(getRemainingUnlockSeconds()),
      BREAK_REFRESH_MS,
    );
    return () => clearInterval(timer);
  }, [onBreak]);

  const takeBreak = async () => {
    try {
      const grant = await temporaryUnlock(5 * 60);
      setRemaining(grant.budgetSeconds);
      setStep(grant.meteringSeconds);
    } catch (error) {
      // Nothing is blocked right now: there is nothing to take a break from.
      if (isScreenTimeError(error) && error.code === "NO_ACTIVE_BLOCK") return;
      throw error;
    }
  };

  if (!onBreak)
    return <Button title="Take a 5-minute break" onPress={takeBreak} />;
  return (
    <View style={{ gap: 4 }}>
      <Text>
        {step < 60
          ? `Break: ${Math.ceil(remaining / 60)} min of app time left`
          : `Break: about ${Math.ceil(remaining / 60)} min of app time left`}
      </Text>
      <Button
        title="End break now"
        onPress={() => {
          relock();
          setRemaining(0);
        }}
      />
    </View>
  );
}

function FocusHome() {
  const [blocked, setBlocked] = useState(isBlocked);
  const attempts = useBlockedAttempts();
  const { events } = useMonitorEvents(() => setBlocked(isBlocked()));
  const lastTarget = attempts.find((attempt) => attempt.target)?.target;
  const lastEvent = events[0];
  const { selectableKinds, usageReports } = getCapabilities();

  useEffect(brandShield, []);

  return (
    <ScrollView contentContainerStyle={{ padding: 20, gap: 16 }}>
      <Text style={{ fontSize: 28, fontWeight: "700" }}>
        {blocked ? "Focusing" : "Ready to focus"}
      </Text>
      <View style={{ height: 360 }}>
        <ActivityPicker
          selectionId={DISTRACTIONS}
          headerText={
            selectableKinds.categories
              ? "Pick distracting apps or whole categories"
              : "Pick distracting apps"
          }
        />
      </View>
      <Button title="Focus for 45 minutes" onPress={() => startSession(45)} />
      <Button title="Focus every weekday, 9–5" onPress={startFocusHours} />
      <Button
        title="Stop weekday focus"
        onPress={() => stopMonitoring(FOCUS_HOURS)}
      />
      <BreakButton />

      <Text>Blocked {attempts.length} times since you opened the app</Text>
      {lastTarget && (
        <View style={{ flexDirection: "row", gap: 8 }}>
          <Text>Most recent:</Text>
          <TokenLabel
            handle={lastTarget.handle}
            tokenType={lastTarget.type}
            displayMode="title-and-icon"
          />
        </View>
      )}
      {lastEvent && (
        <Text>
          {lastEvent.activityName}: {lastEvent.eventType}
        </Text>
      )}

      {usageReports && (
        <DeviceActivityReport filterSegment="daily" style={{ height: 300 }}>
          <Report.VStack spacing={12} padding={16} alignment="leading">
            <Report.Text text="Today" size={13} weight="semibold" uppercase />
            <Report.Text
              bind="totalDuration"
              durationStyle="abbreviated"
              size={34}
              weight="bold"
              monospacedDigit
            />
            <Report.Chart
              type="bar"
              bind="hourly"
              accentColor="#F59E0B"
              gradient
              height={90}
            />
            <Report.TopApps limit={3} spacing={8}>
              <Report.HStack spacing={8}>
                <Report.AppIcon size={24} />
                <Report.AppTitle size={15} />
                <Report.Spacer />
                <Report.Text bind="appDuration" durationStyle="abbreviated" />
              </Report.HStack>
            </Report.TopApps>
          </Report.VStack>
        </DeviceActivityReport>
      )}
    </ScrollView>
  );
}

export default function FocusApp() {
  return (
    <PermissionGate features={["blocking", "monitoring", "usageReports"]}>
      <FocusHome />
    </PermissionGate>
  );
}
