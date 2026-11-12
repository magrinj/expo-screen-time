/**
 * Parental-control reference app, running on the child's device: a daily
 * game limit, bedtime, removal/installation protection, an optional web
 * filter, a weekly report and an activity log. Shared flow — no platform
 * checks.
 */
import {
  ActivityPicker,
  ApplicationLabel,
  CategoryLabel,
  DeviceActivityReport,
  Report,
  WebDomainLabel,
  allowAppInstallation,
  allowAppRemoval,
  blockWebDomains,
  clearMonitorEvents,
  clearReportTemplate,
  deleteSelection,
  denyAppInstallation,
  denyAppRemoval,
  drainShieldInteractions,
  exportSelection,
  getBlockedWebDomains,
  getCapabilities,
  getMonitorEvents,
  getMonitoredActivities,
  getEnforcementGaps,
  isScreenTimeError,
  getSelectionMetadata,
  getTokens,
  importSelection,
  keepCommonSelections,
  listSelections,
  mergeSelections,
  removeToken,
  setReportTemplate,
  setShieldActionsForActivity,
  setShieldConfigForActivity,
  startMonitoring,
  stopAllMonitoring,
  stopMonitoring,
  subtractSelection,
  unblockAllWebDomains,
  unblockWebDomains,
  useMonitorEvents,
  type EnforcementGap,
  type MonitorEvent,
  type ScreenTimeFeature,
  type TokenHandle,
} from "expo-screen-time";
import { useEffect, useState } from "react";
import { Button, ScrollView, Text, View } from "react-native";

import { PermissionGate } from "./shared/PermissionGate";

// Selections, all filled by the parent through <ActivityPicker>.
const GAMES = "games";
const EDUCATIONAL = "educational-games";
const ESSENTIALS = "essentials";
const SCHOOL = "school-apps";
// Derived selections, computed natively from the ones above.
const LIMITED_GAMES = "limited-games";
const BEDTIME_ALLOWED = "bedtime-allowed";
const CONFLICTS = "limited-but-allowed-at-bedtime";

const GAME_LIMIT = "games-limit";
const BEDTIME = "bedtime";
const ADULT_SITES = ["example-adult.com", "example-gambling.com"];

async function applyRules(): Promise<string | null> {
  // Opaque set algebra: games minus the educational ones; bedtime keeps the
  // essentials plus school apps.
  await subtractSelection(LIMITED_GAMES, GAMES, EDUCATIONAL);
  await mergeSelections(BEDTIME_ALLOWED, [ESSENTIALS, SCHOOL]);

  // An app both limited and allowed at bedtime is almost always a mistake.
  await keepCommonSelections(CONFLICTS, [LIMITED_GAMES, BEDTIME_ALLOWED]);
  const conflicts = await getSelectionMetadata(CONFLICTS);
  await deleteSelection(CONFLICTS);
  if (conflicts && conflicts.applicationCount > 0) {
    return `${conflicts.applicationCount} games are also allowed at bedtime.`;
  }

  await startMonitoring({
    activityName: GAME_LIMIT,
    schedule: {
      intervalStart: { hour: 0, minute: 0 },
      intervalEnd: { hour: 23, minute: 59 },
      repeats: true,
    },
    events: [
      {
        eventName: "daily-limit",
        selectionId: LIMITED_GAMES,
        threshold: 60 * 60,
        blockOnThreshold: true,
      },
    ],
  });
  try {
    await startMonitoring({
      activityName: BEDTIME,
      selectionId: BEDTIME_ALLOWED,
      mode: "allowlist",
      schedule: {
        intervalStart: { hour: 21, minute: 0 },
        intervalEnd: { hour: 7, minute: 0 },
        repeats: true,
        warningTime: 10 * 60,
      },
    });
  } catch (error) {
    // An allowlist may hold apps only (same rule on both platforms).
    if (isScreenTimeError(error) && error.code === "INVALID_CONFIG") {
      return "Bedtime keeps apps only — remove the categories and websites.";
    }
    throw error;
  }

  setShieldConfigForActivity(GAME_LIMIT, {
    title: { text: "Game time is over" },
    subtitle: { text: "See you tomorrow!" },
    icon: "gamecontroller.fill",
    primaryButton: { text: "OK" },
    secondaryButton: { text: "Ask for more time" },
  });
  // "Ask for more time" keeps the shield up and is recorded for the parent.
  setShieldActionsForActivity(GAME_LIMIT, {
    primaryAction: "close",
    secondaryAction: "defer",
  });
  setShieldConfigForActivity(BEDTIME, {
    title: { text: "Bedtime" },
    subtitle: { text: "Calls and messages still work." },
    icon: "moon.stars.fill",
    backgroundColor: "#1E1B4B",
    primaryButton: { text: "Good night" },
  });
  setShieldActionsForActivity(BEDTIME, { primaryAction: "close" });

  const { appRemovalProtection, appInstallationBlocking } = getCapabilities();
  if (appRemovalProtection !== "none") denyAppRemoval();
  if (appInstallationBlocking !== "none") denyAppInstallation();
  return null;
}

async function pauseAllRules() {
  await stopAllMonitoring();
  allowAppRemoval();
  allowAppInstallation();
}

/** Back up every selection; restore returns how many came back. */
async function backupSelections(): Promise<Record<string, string>> {
  const backup: Record<string, string> = {};
  for (const id of await listSelections()) {
    const blob = await exportSelection(id);
    if (blob) backup[id] = blob;
  }
  return backup;
}
async function restoreSelections(backup: Record<string, string>) {
  const results = await Promise.all(
    Object.entries(backup).map(([id, blob]) => importSelection(id, blob)),
  );
  // A blob made on the other platform is refused (false), never misread.
  return results.filter(Boolean).length;
}

const LABELS = {
  application: ApplicationLabel,
  category: CategoryLabel,
  webDomain: WebDomainLabel,
} as const;

function SelectionContents({ selectionId }: { selectionId: string }) {
  const [tokens, setTokens] = useState<TokenHandle[]>([]);
  const reload = () =>
    getTokens(selectionId).then((found) => setTokens(found ?? []));

  useEffect(() => {
    reload();
  }, [selectionId]);

  return (
    <View style={{ gap: 6 }}>
      {tokens.map((token) => {
        const Label = LABELS[token.type];
        return (
          <View key={token.handle} style={{ flexDirection: "row", gap: 8 }}>
            <Label
              selectionId={selectionId}
              handle={token.handle}
              displayMode="title-and-icon"
            />
            <Button
              title="Remove"
              onPress={() =>
                removeToken(selectionId, token.handle).then(reload)
              }
            />
          </View>
        );
      })}
    </View>
  );
}

function WebFilter() {
  const { webDomainBlocking } = getCapabilities();
  const [domains, setDomains] = useState(() =>
    webDomainBlocking ? getBlockedWebDomains() : [],
  );
  if (!webDomainBlocking) {
    return <Text>Website filtering isn't available on this device yet.</Text>;
  }
  const update = (change: () => void) => {
    change();
    setDomains(getBlockedWebDomains());
  };
  return (
    <View style={{ gap: 6 }}>
      <Button
        title="Block adult sites"
        onPress={() => update(() => blockWebDomains(ADULT_SITES))}
      />
      {domains.map((domain) => (
        <View key={domain} style={{ flexDirection: "row", gap: 8 }}>
          <Text>{domain}</Text>
          <Button
            title="Allow"
            onPress={() => update(() => unblockWebDomains([domain]))}
          />
        </View>
      ))}
      <Button
        title="Allow every site"
        onPress={() => update(unblockAllWebDomains)}
      />
    </View>
  );
}

/** Tamper evidence for the parent: when protection was off, and why. */
function ProtectionGaps() {
  const [gaps, setGaps] = useState<EnforcementGap[]>([]);
  useEffect(() => {
    getEnforcementGaps(Date.now() - 7 * 24 * 3600 * 1000).then(setGaps);
  }, []);
  if (gaps.length === 0) return <Text>Protection stayed on all week.</Text>;
  return (
    <View style={{ gap: 4 }}>
      {gaps.map((gap) => (
        <Text key={gap.from} style={{ color: "#B91C1C" }}>
          Off from {new Date(gap.from).toLocaleString()}
          {gap.to ? ` to ${new Date(gap.to).toLocaleString()}` : " (still off)"}
          {` · ${gap.reason}`}
        </Text>
      ))}
    </View>
  );
}

function ActivityLog() {
  const [log, setLog] = useState<MonitorEvent[]>([]);
  const append = (incoming: MonitorEvent[]) =>
    setLog((previous) => {
      // Ids make the history and the live stream safe to merge.
      const seen = new Set(previous.map((event) => event.id));
      const fresh = incoming.filter((event) => !seen.has(event.id));
      return [...fresh, ...previous].sort((a, b) => b.timestamp - a.timestamp);
    });

  useEffect(() => {
    getMonitorEvents(undefined, 50).then(append);
  }, []);
  useMonitorEvents((event) => append([event]));

  return (
    <View style={{ gap: 4 }}>
      {log.map((event) => (
        <Text key={event.id}>
          {new Date(event.timestamp).toLocaleTimeString()} ·{" "}
          {event.activityName}
          {" · "}
          {event.eventName ?? event.eventType}
        </Text>
      ))}
      <Button
        title="Clear log"
        onPress={() => clearMonitorEvents().then(() => setLog([]))}
      />
    </View>
  );
}

const WEEKLY_TEMPLATE = (
  <Report.VStack spacing={10} padding={16} alignment="leading">
    <Report.Text text="This week" size={13} weight="semibold" uppercase />
    <Report.Text
      bind="totalDuration"
      durationStyle="short"
      size={28}
      weight="bold"
    />
    <Report.Chart
      type="pie"
      bind="categories"
      pieInnerRadius={0.6}
      height={140}
    />
    <Report.Categories limit={4}>
      <Report.HStack spacing={8}>
        <Report.AppTitle />
        <Report.Spacer />
        <Report.Text bind="appDuration" durationStyle="abbreviated" />
      </Report.HStack>
    </Report.Categories>
    <Report.Gauge bind="totalDuration" goal={14 * 3600} style="linear" />
  </Report.VStack>
);

function GuardianHome() {
  const [rules, setRules] = useState<string[]>([]);
  const [warning, setWarning] = useState<string | null>(null);
  const [moreTimeRequests, setMoreTimeRequests] = useState(0);
  const [backup, setBackup] = useState<Record<string, string> | null>(null);
  const refreshRules = () => getMonitoredActivities().then(setRules);

  useEffect(() => {
    refreshRules();
    // "Ask for more time" presses on the game-limit shield.
    const requests = drainShieldInteractions().filter(
      (press) =>
        press.activityName === GAME_LIMIT && press.button === "secondary",
    );
    setMoreTimeRequests(requests.length);
    // Imperative template: every <DeviceActivityReport> without children uses it.
    setReportTemplate(WEEKLY_TEMPLATE);
    return clearReportTemplate;
  }, []);

  return (
    <ScrollView contentContainerStyle={{ padding: 20, gap: 16 }}>
      <Text style={{ fontSize: 26, fontWeight: "700" }}>Rules</Text>
      {[GAMES, EDUCATIONAL, ESSENTIALS, SCHOOL].map((selectionId) => (
        <View key={selectionId} style={{ height: 280 }}>
          <ActivityPicker selectionId={selectionId} headerText={selectionId} />
        </View>
      ))}
      <Button
        title="Apply rules"
        onPress={async () => {
          setWarning(await applyRules());
          await refreshRules();
        }}
      />
      {warning && <Text style={{ color: "#B45309" }}>{warning}</Text>}
      {rules.map((rule) => (
        <View key={rule} style={{ flexDirection: "row", gap: 8 }}>
          <Text>{rule}</Text>
          <Button
            title="Stop"
            onPress={() => stopMonitoring(rule).then(refreshRules)}
          />
        </View>
      ))}
      <Button
        title="Pause every rule"
        onPress={() => pauseAllRules().then(refreshRules)}
      />
      <Text>{moreTimeRequests} requests for more game time</Text>

      <Text style={{ fontSize: 20, fontWeight: "600" }}>Limited games</Text>
      <SelectionContents selectionId={LIMITED_GAMES} />

      <Text style={{ fontSize: 20, fontWeight: "600" }}>Websites</Text>
      <WebFilter />

      <Text style={{ fontSize: 20, fontWeight: "600" }}>This week</Text>
      <DeviceActivityReport filterSegment="weekly" style={{ height: 340 }} />

      <Text style={{ fontSize: 20, fontWeight: "600" }}>Activity</Text>
      <ActivityLog />
      <ProtectionGaps />

      <Button
        title="Back up selections"
        onPress={() => backupSelections().then(setBackup)}
      />
      {backup && (
        <Button
          title="Restore selections"
          onPress={() => restoreSelections(backup)}
        />
      )}
    </ScrollView>
  );
}

export default function GuardianApp() {
  const features: ScreenTimeFeature[] = [
    "blocking",
    "monitoring",
    "usageReports",
    "appRemovalProtection",
  ];
  if (getCapabilities().webDomainBlocking) features.push("webDomainBlocking");
  return (
    <PermissionGate member="child" features={features}>
      <GuardianHome />
    </PermissionGate>
  );
}
