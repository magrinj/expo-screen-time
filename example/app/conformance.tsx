/**
 * Behavioural conformance suite.
 *
 * The same scripted scenarios run on every platform through the public API.
 * They branch on `getCapabilities()`, never on `Platform.OS`: where a capability
 * is off, the scenario asserts the coded `UNSUPPORTED` error instead of the
 * behaviour. Permissions have no capability: C02 and C11 branch on whether
 * `getScreenTimePermissionsAsync` answers (see `permissionsIfImplemented`). Driven by `example/conformance/suite.ad` (agent-device), which
 * taps "Run" and waits for the verdict line.
 *
 * Shields are drawn by the OS outside the accessibility tree, so what they
 * cover is not asserted here: the API-observable half (state, events, errors)
 * is. Screenshots of the shields are the other half of the evidence.
 *
 * Fixtures, picked once with the buttons below:
 * - `conformance_apps`: at least one app;
 * - `conformance_category`: at least one category.
 */
import {
  blockSelection,
  clearMonitorEvents,
  drainShieldInteractions,
  getCapabilities,
  getEnforcementGaps,
  getMonitorEvents,
  getMonitoredActivities,
  getRemainingUnlockSeconds,
  getScreenTimePermissionsAsync,
  getSelectionMetadata,
  getTokens,
  isBlocked,
  isScreenTimeError,
  isTemporarilyUnlocked,
  openApplication,
  relock,
  requestScreenTimePermissionsAsync,
  startMonitoring,
  stopAllMonitoring,
  stopMonitoring,
  temporaryUnlock,
  unblockAll,
  type DateComponents,
  type PermissionStepId,
  type ScreenTimeCapabilities,
  type ScreenTimePermissionOptions,
  type ScreenTimePermissionResponse,
} from "expo-screen-time";
import { router } from "expo-router";
import { useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";

const APPS = "conformance_apps";
const CATEGORY = "conformance_category";
const ACTIVITY = "conformance_schedule";

// The contract's vocabulary (`PermissionStep`), checked on every platform.
const STEP_IDS: PermissionStepId[] = ["screenTime", "usageAccess", "overlay", "accessibility", "notifications", "batteryOptimization", "backgroundAutostart", "deviceAdmin"];
const STEP_STATUSES = ["granted", "denied", "undetermined", "restricted", "unknown"];
const STEP_KINDS = ["prompt", "settings", "guide"];

type Status = "pass" | "fail";
type Result = { id: string; title: string; status: Status; detail: string };
type Scenario = { id: string; title: string; run: (caps: ScreenTimeCapabilities) => Promise<string> };

class Failure extends Error {}

function expect(condition: boolean, message: string): void {
  if (!condition) throw new Failure(message);
}

/** Runs `call` (sync or async) and asserts it fails with `code`. */
async function expectCode(code: string, call: () => unknown): Promise<void> {
  try {
    await call();
  } catch (error) {
    expect(isScreenTimeError(error), `expected a ScreenTimeError ${code}, got ${String(error)}`);
    expect((error as { code: string }).code === code, `expected ${code}, got ${(error as { code: string }).code}`);
    return;
  }
  throw new Failure(`expected ${code}, the call succeeded`);
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Hour and minute `minutes` from now. */
function at(minutes: number): DateComponents {
  const date = new Date(Date.now() + minutes * 60_000);
  return { hour: date.getHours(), minute: date.getMinutes() };
}

/** Waits up to `ms` for `predicate`, polling. */
async function eventually(predicate: () => Promise<boolean>, ms = 4000): Promise<boolean> {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await predicate()) return true;
    await sleep(250);
  }
  return predicate();
}

/**
 * The permission response, or null where the permission steps are not
 * implemented. Permissions have no capability field: a platform that can
 * list its steps answers, one that cannot rejects with `UNSUPPORTED` (the
 * Android skeleton before stage 3). The condition is the call itself, never
 * the platform.
 */
async function permissionsIfImplemented(options?: ScreenTimePermissionOptions): Promise<ScreenTimePermissionResponse | null> {
  try {
    return await getScreenTimePermissionsAsync(options);
  } catch (error) {
    if (isScreenTimeError(error) && error.code === "UNSUPPORTED") return null;
    throw error;
  }
}

async function reset(caps: ScreenTimeCapabilities): Promise<void> {
  if (caps.blocking === "none") return;
  await stopAllMonitoring();
  unblockAll();
  await clearMonitorEvents();
  drainShieldInteractions();
}

async function requireFixture(selectionId: string, kind: "applicationCount" | "categoryCount"): Promise<void> {
  const metadata = await getSelectionMetadata(selectionId);
  expect(!!metadata && metadata[kind] > 0, `fixture "${selectionId}" needs at least one ${kind === "applicationCount" ? "app" : "category"}: pick it first`);
}

const scenarios: Scenario[] = [
  {
    id: "C01",
    title: "Capabilities are complete and consistent",
    async run(caps) {
      expect(["enforced", "friction", "none"].includes(caps.blocking), `blocking=${caps.blocking}`);
      expect(caps.limits.minIntervalSeconds === 900, `minIntervalSeconds=${caps.limits.minIntervalSeconds}`);
      expect(caps.limits.maxIntervalSeconds === 604800, `maxIntervalSeconds=${caps.limits.maxIntervalSeconds}`);
      expect(caps.limits.maxMonitoredActivities === 19, `maxMonitoredActivities=${caps.limits.maxMonitoredActivities}`);
      if (!caps.openApplication) await expectCode("UNSUPPORTED", () => openApplication("conformance"));
      return `blocking=${caps.blocking}, usageReports=${caps.usageReports}, shieldOpenApp=${caps.shieldOpenApp}`;
    },
  },
  {
    id: "C02",
    title: "Permissions report their steps",
    async run(caps) {
      const response = await permissionsIfImplemented();
      if (!response) {
        expect(caps.blocking === "none", "permissions must be implemented wherever blocking is");
        return "UNSUPPORTED (not implemented)";
      }
      const { steps } = response;
      expect(steps.length > 0, "at least one step");
      for (const s of steps) {
        expect(STEP_IDS.includes(s.id) && STEP_STATUSES.includes(s.status) && STEP_KINDS.includes(s.kind) && typeof s.required === "boolean", `step shape: ${JSON.stringify(s)}`);
        expect(s.error === undefined || (typeof s.error.code === "string" && typeof s.error.message === "string"), `step error shape: ${JSON.stringify(s.error)}`);
      }
      expect(new Set(steps.map((s) => s.id)).size === steps.length, "step ids are unique");
      const required = steps.filter((s) => s.required);
      expect(required.length > 0, "a required step");
      // The derivation rules of the contract.
      expect(response.granted === required.every((s) => s.status === "granted"), "granted iff every required step is granted");
      const expectedStatus = response.granted ? "granted" : required.some((s) => s.status === "denied" || s.status === "restricted") ? "denied" : "undetermined";
      expect(response.status === expectedStatus, `status=${response.status}, expected ${expectedStatus} from the steps`);
      expect(!required.some((s) => s.status === "restricted") || !response.canAskAgain, "a restricted required step means canAskAgain is false");
      // The rest of the suite blocks and monitors: that needs the access.
      if (caps.blocking !== "none") expect(response.granted, `access must be granted to run the suite (status=${response.status})`);
      return `${response.status}, ${steps.length} step(s): ${steps.map((s) => `${s.id}=${s.status}${s.required ? "" : " (recommended)"}`).join(", ")}`;
    },
  },
  {
    id: "C11",
    title: "Features decide the required steps",
    async run(caps) {
      const plain = await permissionsIfImplemented({ features: ["blocking"] });
      if (!plain) {
        expect(caps.blocking === "none", "permissions must be implemented wherever blocking is");
        await expectCode("UNSUPPORTED", () => requestScreenTimePermissionsAsync({ features: ["blocking"], step: "usageAccess" }));
        return "UNSUPPORTED (not implemented)";
      }
      expect(!plain.steps.some((s) => s.id === "notifications" && s.required), "no required notifications step without shieldOpenApp");
      const withOpenApp = await getScreenTimePermissionsAsync({ features: ["blocking", "shieldOpenApp"] });
      const notifications = withOpenApp.steps.find((s) => s.id === "notifications");
      if (caps.shieldOpenApp === "notification") {
        expect(!!notifications && notifications.required, "shieldOpenApp through a notification requires the notifications step");
      } else {
        expect(!notifications?.required, "a direct shieldOpenApp does not require the notifications step");
      }
      expect(withOpenApp.granted === withOpenApp.steps.filter((s) => s.required).every((s) => s.status === "granted"), "granted iff every required step is granted");
      // A step the features do not list is a caller error, whichever platform: pick one from the response.
      const unlisted = STEP_IDS.find((id) => !plain.steps.some((s) => s.id === id));
      expect(!!unlisted, "a step id the features do not list");
      await expectCode("INVALID_CONFIG", () => requestScreenTimePermissionsAsync({ features: ["blocking"], step: unlisted }));
      return `notifications step ${notifications ? `${notifications.status}${notifications.required ? ", required" : ", recommended"}` : "not listed"}; unlisted step ${unlisted} rejected`;
    },
  },
  {
    id: "C03",
    title: "Rejections carry typed codes",
    async run(caps) {
      if (caps.blocking === "none") {
        await expectCode("UNSUPPORTED", () => temporaryUnlock(60));
        return "UNSUPPORTED (blocking off)";
      }
      await reset(caps);
      await expectCode("NO_ACTIVE_BLOCK", () => temporaryUnlock(60));
      await expectCode("SELECTION_NOT_FOUND", () => blockSelection("conformance_missing"));
      await expectCode("INVALID_CONFIG", () =>
        startMonitoring({ activityName: "expo_st_reserved", selectionId: APPS, schedule: { intervalStart: at(0), intervalEnd: at(20), repeats: false } }),
      );
      await expectCode("INVALID_CONFIG", () => stopMonitoring("expo_st_temporary_unlock"));
      return "NO_ACTIVE_BLOCK, SELECTION_NOT_FOUND, INVALID_CONFIG ×2";
    },
  },
  {
    id: "C04",
    title: "Manual block on and off",
    async run(caps) {
      if (caps.blocking === "none") {
        await expectCode("UNSUPPORTED", () => blockSelection(APPS));
        return "UNSUPPORTED (blocking off)";
      }
      await requireFixture(APPS, "applicationCount");
      await reset(caps);
      blockSelection(APPS);
      expect(isBlocked(), "isBlocked() after blockSelection");
      unblockAll();
      expect(!isBlocked(), "isBlocked() false after unblockAll");
      return "blocked, then lifted";
    },
  },
  {
    id: "C05",
    title: "Allowlist validates and blocks",
    async run(caps) {
      if (caps.blocking === "none") {
        await expectCode("UNSUPPORTED", () => blockSelection(APPS, { mode: "allowlist" }));
        return "UNSUPPORTED (blocking off)";
      }
      await requireFixture(APPS, "applicationCount");
      await requireFixture(CATEGORY, "categoryCount");
      await reset(caps);
      await expectCode("INVALID_CONFIG", () => blockSelection(CATEGORY, { mode: "allowlist" }));
      blockSelection(APPS, { mode: "allowlist" });
      expect(isBlocked(), "isBlocked() under an allowlist");
      await expectCode("UNSUPPORTED", () => temporaryUnlock(60));
      unblockAll();
      expect(!isBlocked(), "isBlocked() false after unblockAll");
      return "category rejected, allowlist on, untargeted unlock UNSUPPORTED, lifted";
    },
  },
  {
    id: "C06",
    title: "Schedule starting now: one start, one end, one activity",
    async run(caps) {
      if (caps.blocking === "none") {
        await expectCode("UNSUPPORTED", () =>
          startMonitoring({ activityName: ACTIVITY, selectionId: APPS, schedule: { intervalStart: at(0), intervalEnd: at(20), repeats: false } }),
        );
        return "UNSUPPORTED (blocking off)";
      }
      await requireFixture(APPS, "applicationCount");
      await reset(caps);
      await startMonitoring({ activityName: ACTIVITY, selectionId: APPS, schedule: { intervalStart: at(0), intervalEnd: at(20), repeats: false } });
      const activities = await getMonitoredActivities();
      expect(activities.length === 1 && activities[0] === ACTIVITY, `getMonitoredActivities()=${JSON.stringify(activities)}`);
      expect(isBlocked(), "the block applies at once inside the interval");
      const started = await eventually(async () => (await getMonitorEvents(ACTIVITY)).some((e) => e.eventType === "intervalDidStart"));
      expect(started, "intervalDidStart recorded");
      await stopMonitoring(ACTIVITY);
      expect((await getMonitoredActivities()).length === 0, "no activity after stopMonitoring");
      const ended = await eventually(async () => (await getMonitorEvents(ACTIVITY)).some((e) => e.eventType === "intervalDidEnd"));
      expect(ended, "intervalDidEnd recorded");
      const events = await getMonitorEvents(ACTIVITY);
      const count = (type: string) => events.filter((e) => e.eventType === type).length;
      expect(count("intervalDidStart") === 1 && count("intervalDidEnd") === 1, `events: ${events.map((e) => e.eventType).join(", ")}`);
      expect(new Set(events.map((e) => e.id)).size === events.length, "event ids are unique");
      expect(!isBlocked(), "isBlocked() false once the only source stopped");
      return "start ×1, end ×1, unique ids, block on then off";
    },
  },
  {
    id: "C07",
    title: "Schedule limits are enforced",
    async run(caps) {
      if (caps.blocking === "none") return "skipped by C06 (UNSUPPORTED)";
      await reset(caps);
      await expectCode("INTERVAL_TOO_SHORT", () =>
        startMonitoring({ activityName: ACTIVITY, selectionId: APPS, schedule: { intervalStart: at(0), intervalEnd: at(5), repeats: false } }),
      );
      return "a 5-minute interval rejects INTERVAL_TOO_SHORT";
    },
  },
  {
    id: "C08",
    title: "Temporary unlock, renewal and relock",
    async run(caps) {
      if (caps.blocking === "none") return "covered by C03 (UNSUPPORTED)";
      await requireFixture(APPS, "applicationCount");
      await reset(caps);
      blockSelection(APPS);
      const grant = await temporaryUnlock(60);
      expect(grant.budgetSeconds === 60, `budgetSeconds=${grant.budgetSeconds}`);
      expect(grant.meteringSeconds > 0 && grant.meteringSeconds <= 60, `meteringSeconds=${grant.meteringSeconds}`);
      expect(isTemporarilyUnlocked() && !isBlocked(), "unlocked and not blocked during the grant");
      expect(getRemainingUnlockSeconds() === 60, `remaining=${getRemainingUnlockSeconds()}`);
      const renewal = await temporaryUnlock(120);
      expect(renewal.budgetSeconds === 120 && getRemainingUnlockSeconds() === 120, "a renewal replaces the budget");
      relock();
      expect(isBlocked() && !isTemporarilyUnlocked(), "relock restores the block");
      unblockAll();
      return "grant 60, renew 120, relock";
    },
  },
  {
    id: "C10",
    title: "Targeted unlock lifts one target",
    async run(caps) {
      if (caps.blocking === "none") return "covered by C03 (UNSUPPORTED)";
      await requireFixture(APPS, "applicationCount");
      await reset(caps);
      const [target] = (await getTokens(APPS)) ?? [];
      expect(!!target, "a token handle from the apps fixture");
      await expectCode("NO_ACTIVE_BLOCK", () => temporaryUnlock(60, { target }));
      blockSelection(APPS);
      await expectCode("INVALID_CONFIG", () => temporaryUnlock(60, { target: { handle: "not-a-handle", type: "application" } }));
      const grant = await temporaryUnlock(60, { target });
      expect(grant.budgetSeconds === 60 && grant.meteringSeconds > 0, `budget=${grant.budgetSeconds}, metering=${grant.meteringSeconds}`);
      expect(grant.target?.handle === target.handle, "the grant names its target");
      expect(!isTemporarilyUnlocked(), "a targeted grant is not the whole-block unlock");
      relock();
      unblockAll();
      return `lifted one ${target.type}, metering ${grant.meteringSeconds}s; NO_ACTIVE_BLOCK and INVALID_CONFIG checked`;
    },
  },
  {
    id: "C09",
    title: "Shield interactions and enforcement gaps are well-formed",
    async run(caps) {
      if (caps.blocking === "none") {
        await expectCode("UNSUPPORTED", () => drainShieldInteractions());
        await expectCode("UNSUPPORTED", () => getEnforcementGaps());
        return "UNSUPPORTED (blocking off)";
      }
      const interactions = drainShieldInteractions();
      for (const i of interactions) {
        expect(["primary", "secondary"].includes(i.button) && typeof i.timestamp === "number", "interaction shape");
        expect(i.target === null || ["application", "category", "webDomain"].includes(i.target.type), "interaction target");
      }
      const gaps = await getEnforcementGaps();
      for (const g of gaps) expect(typeof g.from === "number" && (g.to === null || g.to >= g.from), "gap shape");
      const open = await getEnforcementGaps(Date.now());
      expect(open.every((g) => g.to === null || g.to >= Date.now() - 1000), "since keeps gaps still in progress");
      return `${interactions.length} interaction(s), ${gaps.length} gap(s)`;
    },
  },
];

export default function Conformance() {
  const [results, setResults] = useState<Result[]>([]);
  const [running, setRunning] = useState(false);

  const run = async () => {
    setRunning(true);
    setResults([]);
    const caps = getCapabilities();
    const done: Result[] = [];
    for (const scenario of scenarios) {
      let result: Result;
      try {
        result = { id: scenario.id, title: scenario.title, status: "pass", detail: await scenario.run(caps) };
      } catch (error) {
        result = { id: scenario.id, title: scenario.title, status: "fail", detail: error instanceof Error ? error.message : String(error) };
      }
      done.push(result);
      setResults([...done]);
    }
    try {
      await reset(caps);
    } catch {
      // leave the device as clean as possible; the verdict below already reports failures
    }
    setRunning(false);
  };

  const failed = results.filter((r) => r.status === "fail").length;
  const verdict =
    running || results.length < scenarios.length
      ? running
        ? `RUNNING ${results.length}/${scenarios.length}`
        : "NOT RUN"
      : failed === 0
        ? `CONFORMANCE PASS ${results.length}/${scenarios.length}`
        : `CONFORMANCE FAIL ${failed}/${scenarios.length}`;

  return (
    // The root stack header is translucent: let iOS inset the content below it.
    <ScrollView contentInsetAdjustmentBehavior="automatic" contentContainerStyle={styles.page}>
      <View style={styles.row}>
        <Button title="Pick apps" onPress={() => router.push({ pathname: "/select-apps", params: { id: APPS } })} />
        <Button title="Pick a category" onPress={() => router.push({ pathname: "/select-apps", params: { id: CATEGORY } })} />
        <Button title="Run" onPress={run} disabled={running} />
      </View>
      <Text testID="conformance-verdict" style={[styles.verdict, failed > 0 && styles.failText]}>
        {verdict}
      </Text>
      {results.map((r) => (
        <View key={r.id} testID={`conformance-${r.id}`} style={styles.card}>
          <Text style={[styles.title, r.status === "fail" && styles.failText]}>
            {r.status === "pass" ? "PASS" : "FAIL"} {r.id} {r.title}
          </Text>
          <Text style={styles.detail}>{r.detail}</Text>
        </View>
      ))}
    </ScrollView>
  );
}

function Button({ title, onPress, disabled }: { title: string; onPress: () => void; disabled?: boolean }) {
  return (
    <Pressable accessibilityRole="button" onPress={onPress} disabled={disabled} style={[styles.button, disabled && styles.disabled]}>
      <Text style={styles.buttonText}>{title}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  page: { padding: 16, gap: 10 },
  row: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  button: { backgroundColor: "#007AFF", paddingHorizontal: 12, paddingVertical: 8, borderRadius: 8 },
  disabled: { opacity: 0.5 },
  buttonText: { color: "white", fontWeight: "600" },
  verdict: { fontSize: 16, fontWeight: "700", color: "#1a7f37" },
  failText: { color: "#c62828" },
  card: { padding: 10, borderRadius: 8, backgroundColor: "#f3f4f6", gap: 2 },
  title: { fontWeight: "600" },
  detail: { color: "#555", fontSize: 12 },
});
