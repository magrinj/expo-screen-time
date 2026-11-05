import {
  DeviceActivityReport,
  FilterSegment,
  Report,
  getCapabilities,
  getScreenTimePermissionsAsync,
  requestScreenTimePermissionsAsync,
  type PermissionStep,
} from "expo-screen-time";
import React, { useCallback, useEffect, useState } from "react";
import {
  ScrollView,
  StyleSheet,
  Text,
  View,
  TouchableOpacity,
  useColorScheme,
} from "react-native";

type SegmentOption = "daily" | "weekly";
type Demo = "overview" | "charts" | "lists" | "typography";

const DEMOS: { key: Demo; label: string; height: number }[] = [
  { key: "overview", label: "Overview", height: 460 },
  { key: "charts", label: "Charts", height: 650 },
  { key: "lists", label: "Lists", height: 460 },
  { key: "typography", label: "Typography", height: 480 },
];

const ACCENT = "#AF52DE";
const GOAL = 4 * 3600; // 4h daily goal

/** Grid of stat tiles + gauge + progress — box props, gradients, shadows. */
function OverviewTemplate() {
  return (
    <Report.VStack spacing={14} padding={16} alignment="leading">
      <Report.HStack spacing={14} alignment="center">
        <Report.Gauge bind="totalDuration" goal={GOAL} size={70} color={ACCENT} />
        <Report.VStack spacing={2} alignment="leading">
          <Report.Text text="screen time" uppercase size={11} color="#8E8E93" letterSpacing={1.2} />
          <Report.Text
            bind="totalDuration"
            durationStyle="abbreviated"
            size={32}
            weight="bold"
            design="rounded"
            monospacedDigit
          />
          <Report.Text bind="totalDuration" format="percent" goal={GOAL} size={12} color={ACCENT} weight="semibold" />
        </Report.VStack>
        <Report.Spacer />
      </Report.HStack>

      <Report.ProgressBar bind="totalDuration" goal={GOAL} height={10} color={ACCENT} trackColor="#E5E5EA" />

      <Report.Grid columns={2} spacing={10} fillWidth>
        <Report.VStack
          spacing={4}
          padding={12}
          backgroundGradient={["#AF52DE", "#5E5CE6"]}
          gradientDirection="diagonal"
          cornerRadius={14}
          shadowColor="#00000030"
          shadowRadius={6}
          shadowY={3}
        >
          <Report.Text bind="pickups" size={24} weight="bold" color="#FFFFFF" design="rounded" />
          <Report.Text text="Pickups" size={11} color="#FFFFFFB0" />
        </Report.VStack>
        <Report.VStack
          spacing={4}
          padding={12}
          backgroundColor="#FFFFFF"
          cornerRadius={14}
          borderColor="#E5E5EA"
          borderWidth={1}
        >
          <Report.Text bind="appCount" size={24} weight="bold" color="#1C1C1E" design="rounded" />
          <Report.Text text="Apps used" size={11} color="#8E8E93" />
        </Report.VStack>
        <Report.VStack spacing={4} padding={12} backgroundColor="#F2F2F7" cornerRadius={14}>
          <Report.Text bind="longestActivity" durationStyle="short" size={20} weight="semibold" color="#1C1C1E" />
          <Report.Text text="Longest session" size={11} color="#8E8E93" />
        </Report.VStack>
        <Report.VStack spacing={4} padding={12} backgroundColor="#F2F2F7" cornerRadius={14}>
          <Report.Text bind="firstPickup" size={20} weight="semibold" color="#1C1C1E" />
          <Report.Text text="First pickup" size={11} color="#8E8E93" />
        </Report.VStack>
      </Report.Grid>

      <Report.HStack spacing={8} alignment="center">
        <Report.Symbol name="hourglass" size={14} color={ACCENT} renderingMode="hierarchical" />
        <Report.Text text="Daily goal: 4h" size={12} color="#8E8E93" />
        <Report.Spacer />
        <Report.Gauge bind="pickups" goal={100} style="linear" color={ACCENT} />
      </Report.HStack>
    </Report.VStack>
  );
}

/** Every chart type + rule marks, palettes, donut, axis control. */
function ChartsTemplate() {
  return (
    <Report.VStack spacing={12} padding={16} alignment="leading">
      <Report.Text text="Hourly · bar + average + goal" size={12} color="#8E8E93" />
      <Report.Chart
        type="bar"
        bind="hourly"
        accentColor={ACCENT}
        barCornerRadius={3}
        averageLine
        goalValue={30}
        goalColor="#FF3B30"
        height={100}
        showXAxis
        xAxisStride={6}
        showYAxis={false}
      />

      <Report.Text text="Hourly · smooth line + points" size={12} color="#8E8E93" />
      <Report.Chart
        type="line"
        bind="hourly"
        accentColor="#5E5CE6"
        lineWidth={3}
        showPoints
        height={90}
        showYAxis={false}
        showXAxis={false}
      />

      <Report.Text text="Hourly · gradient area" size={12} color="#8E8E93" />
      <Report.Chart type="area" bind="hourly" accentColor="#34C759" gradient height={90} showYAxis={false} showXAxis={false} />

      <Report.Text text="Categories · donut with palette (iOS 17)" size={12} color="#8E8E93" />
      <Report.Chart
        type="pie"
        bind="categories"
        colorPalette={["#AF52DE", "#5E5CE6", "#34C759", "#FF9500", "#FF3B30"]}
        pieInnerRadius={0.6}
        height={120}
      />
    </Report.VStack>
  );
}

/** Ranked app/category lists with real icons — item scope bindings. */
function ListsTemplate() {
  return (
    <Report.VStack spacing={14} padding={16} alignment="leading">
      <Report.Text text="top apps" uppercase size={11} color="#8E8E93" letterSpacing={1.2} />
      <Report.TopApps limit={4} spacing={10} emptyText="No app usage yet">
        <Report.HStack spacing={10} alignment="center">
          <Report.Text bind="rank" size={13} weight="semibold" color="#C7C7CC" monospacedDigit />
          <Report.AppIcon size={28} cornerRadius={7} />
          <Report.VStack spacing={1} alignment="leading">
            <Report.AppTitle size={14} weight="medium" />
            <Report.Text bind="appDuration" durationStyle="short" size={11} color="#8E8E93" />
          </Report.VStack>
          <Report.Spacer />
          <Report.Gauge bind="appDuration" goal={3600} size={34} color={ACCENT} />
        </Report.HStack>
      </Report.TopApps>

      <Report.Divider color="#E5E5EA" />

      <Report.Text text="categories" uppercase size={11} color="#8E8E93" letterSpacing={1.2} />
      <Report.Categories limit={3} spacing={8} emptyText="No categories yet">
        <Report.HStack spacing={10} alignment="center">
          <Report.AppIcon size={22} cornerRadius={5} />
          <Report.AppTitle size={13} />
          <Report.Spacer />
          <Report.Text bind="appDuration" durationStyle="abbreviated" size={12} color="#8E8E93" monospacedDigit />
        </Report.HStack>
      </Report.Categories>
    </Report.VStack>
  );
}

/** Font designs, decorations, symbol rendering modes. */
function TypographyTemplate() {
  return (
    <Report.VStack spacing={12} padding={16} alignment="leading">
      <Report.Text bind="totalDuration" durationStyle="full" size={22} weight="bold" design="default" />
      <Report.Text bind="totalDuration" durationStyle="full" size={22} weight="bold" design="rounded" color={ACCENT} />
      <Report.Text bind="totalDuration" durationStyle="full" size={22} weight="semibold" design="serif" />
      <Report.Text bind="totalDuration" durationStyle="full" size={20} design="monospaced" />

      <Report.Divider />

      <Report.Text text="italic" italic size={15} />
      <Report.Text text="underline" underline size={15} />
      <Report.Text text="strikethrough" strikethrough size={15} color="#8E8E93" />
      <Report.Text text="uppercase + tracking" uppercase letterSpacing={2} size={13} weight="semibold" />
      <Report.Text text="light · heavy" size={15} weight="light" />
      <Report.Text text="light · heavy" size={15} weight="heavy" />

      <Report.Divider />

      <Report.HStack spacing={14}>
        <Report.Symbol name="hourglass.bottomhalf.filled" size={26} color={ACCENT} renderingMode="hierarchical" />
        <Report.Symbol name="chart.bar.fill" size={26} color="#5E5CE6" renderingMode="hierarchical" />
        <Report.Symbol name="apps.iphone" size={26} color="#34C759" />
        <Report.Symbol name="hand.raised.fill" size={26} color="#FF9500" weight="bold" />
      </Report.HStack>
    </Report.VStack>
  );
}

const TEMPLATES: Record<Demo, () => React.JSX.Element> = {
  overview: OverviewTemplate,
  charts: ChartsTemplate,
  lists: ListsTemplate,
  typography: TypographyTemplate,
};

/**
 * The permission steps the usage report needs that are not granted yet
 * (`screenTime` on iOS, `usageAccess` on Android), read through the public
 * API rather than the platform: the banner below shows while any is missing
 * and requests the next one.
 */
function useMissingReportSteps(enabled: boolean) {
  const [missing, setMissing] = useState<PermissionStep[]>([]);
  const refresh = useCallback(async () => {
    if (!enabled) return;
    try {
      const response = await getScreenTimePermissionsAsync({ features: ["usageReports"] });
      setMissing(response.steps.filter((step) => step.required && step.status !== "granted"));
    } catch {
      setMissing([]);
    }
  }, [enabled]);
  useEffect(() => {
    refresh();
  }, [refresh]);
  const request = useCallback(async () => {
    try {
      await requestScreenTimePermissionsAsync({ features: ["usageReports"] });
    } finally {
      refresh();
    }
  }, [refresh]);
  return { missing, request, refresh };
}

export default function AnalyticsScreen() {
  const [segment, setSegment] = useState<SegmentOption>("daily");
  const [demo, setDemo] = useState<Demo>("overview");
  const [reportKey, setReportKey] = useState(0);
  const usageReports = getCapabilities().usageReports;
  const { missing, request } = useMissingReportSteps(usageReports);
  // The report's default colours follow the app's light/dark scheme (as the
  // iOS extension follows the host's traits): the card behind it must too.
  const dark = useColorScheme() === "dark";

  const Template = TEMPLATES[demo];
  const height = DEMOS.find((d) => d.key === demo)?.height ?? 460;

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Segment Picker */}
      <View style={styles.segmentContainer}>
        {(["daily", "weekly"] as const).map((option) => (
          <TouchableOpacity
            key={option}
            style={[
              styles.segmentButton,
              segment === option && styles.segmentButtonActive,
            ]}
            onPress={() => setSegment(option)}
          >
            <Text
              style={[
                styles.segmentText,
                segment === option && styles.segmentTextActive,
              ]}
            >
              {option === "daily" ? "Daily" : "Weekly"}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      {/* Demo chips — swapping demos swaps the live template */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chips}>
        {DEMOS.map(({ key, label }) => (
          <TouchableOpacity
            key={key}
            style={[styles.chip, demo === key && styles.chipActive]}
            onPress={() => setDemo(key)}
          >
            <Text style={[styles.chipText, demo === key && styles.chipTextActive]}>
              {label}
            </Text>
          </TouchableOpacity>
        ))}
      </ScrollView>

      {usageReports && missing.length > 0 && (
        <View style={styles.banner}>
          <Text style={styles.bannerText}>
            The report needs {missing.map((step) => step.id).join(", ")} to show real data.
          </Text>
          <TouchableOpacity style={styles.bannerButton} onPress={request}>
            <Text style={styles.bannerButtonText}>Grant access</Text>
          </TouchableOpacity>
        </View>
      )}

      {/* The whole report layout below is JSX (Report.* components), serialized
          and rendered natively: by the sandboxed report extension on iOS, by
          the module's renderer on Android. The container height must fit the
          template (the native view can't auto-size). Remounting re-reads the
          usage data (the report is not live). */}
      <DeviceActivityReport
        key={reportKey}
        filterSegment={segment as FilterSegment}
        style={[styles.card, { height, backgroundColor: dark ? "#1C1C1E" : "#FFFFFF" }]}
      >
        <Template />
      </DeviceActivityReport>

      <View style={styles.footer}>
        <TouchableOpacity onPress={() => setReportKey((k) => k + 1)}>
          <Text style={styles.footerLink}>Refresh</Text>
        </TouchableOpacity>
        <Text style={styles.footerText}>
          Layout authored in JSX, rendered natively from the device's usage data.
          {"\n"}Usage values never reach JavaScript.
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#F2F2F7",
  },
  content: {
    padding: 16,
    paddingBottom: 32,
  },
  segmentContainer: {
    flexDirection: "row",
    backgroundColor: "#E5E5EA",
    borderRadius: 8,
    padding: 2,
    marginBottom: 12,
  },
  segmentButton: {
    flex: 1,
    paddingVertical: 8,
    alignItems: "center",
    borderRadius: 6,
  },
  segmentButtonActive: {
    backgroundColor: "#FFFFFF",
    shadowColor: "#000",
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.1,
    shadowRadius: 2,
  },
  segmentText: {
    fontSize: 14,
    fontWeight: "500",
    color: "#8E8E93",
  },
  segmentTextActive: {
    color: "#007AFF",
  },
  chips: {
    flexGrow: 0,
    marginBottom: 12,
  },
  chip: {
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 16,
    backgroundColor: "#E5E5EA",
    marginRight: 8,
  },
  chipActive: {
    backgroundColor: "#AF52DE",
  },
  chipText: {
    fontSize: 13,
    fontWeight: "600",
    color: "#8E8E93",
  },
  chipTextActive: {
    color: "#FFFFFF",
  },
  card: {
    backgroundColor: "#FFFFFF",
    borderRadius: 14,
    overflow: "hidden",
  },
  banner: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    backgroundColor: "#FFF4E5",
    borderRadius: 10,
    padding: 12,
    marginBottom: 12,
  },
  bannerText: {
    flex: 1,
    fontSize: 13,
    color: "#7A4B00",
  },
  bannerButton: {
    backgroundColor: "#AF52DE",
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 8,
  },
  bannerButtonText: {
    color: "#FFFFFF",
    fontSize: 13,
    fontWeight: "600",
  },
  footer: {
    marginTop: 16,
    padding: 16,
    alignItems: "center",
    gap: 8,
  },
  footerLink: {
    fontSize: 13,
    fontWeight: "600",
    color: "#AF52DE",
  },
  footerText: {
    fontSize: 12,
    color: "#8E8E93",
    textAlign: "center",
    lineHeight: 18,
  },
});
