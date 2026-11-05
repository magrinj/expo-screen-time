import { requireNativeView } from "expo";
import { useEffect, useRef, useState, type ReactElement, type ReactNode } from "react";
import {
  Platform,
  type StyleProp,
  View,
  type ViewStyle,
  Text,
  StyleSheet,
} from "react-native";

import { getCapabilities } from "./functions";
import { serializeTemplate } from "./report/serializeTemplate";
import {
  acquireTemplate,
  hashTemplate,
  releaseTemplate,
} from "./report/templateChannel";

/**
 * Filter segment for time-based filtering
 */
export type FilterSegment = "daily" | "weekly";

/**
 * Props for DeviceActivityReport component
 */
export interface DeviceActivityReportProps {
  /** Time filter segment */
  filterSegment?: FilterSegment;

  /** Date to filter by (defaults to today, captured when the view mounts) */
  filterDate?: Date;

  /** Container layout style */
  style?: StyleProp<ViewStyle>;

  /**
   * The report template, built from `Report.*` components. The tree is
   * serialized (never rendered by React) and handed to the native renderer:
   * on iOS the report extension, through the App Group; on Android the
   * module's own renderer, in the app process. Values are resolved natively
   * on both platforms and never reach JavaScript.
   *
   * Omit children only when pairing with the imperative
   * `setReportTemplate(element)` — with no template at all, the report
   * renders a "No custom template set" placeholder.
   *
   * One template is active at a time (the native slot is a single channel) —
   * the last mounted report with children wins.
   *
   * **Sizing:** the report cannot report its content height back to React
   * Native — the template is clipped to this component's `style`. Size the
   * container to fit your template (e.g. a fixed `height`).
   */
  children?: ReactNode;
}

// Native props interface for the view
interface NativeReportViewProps {
  filterSegment: string;
  filterDate: number;
  /** Hash of the active template — changes force the native side to re-read
   * it (the SwiftUI view identity on iOS, a render on Android). */
  templateHash?: string;
  style?: StyleProp<ViewStyle>;
}

// Native view registered as a named view on the ExpoScreenTime module on both
// platforms (ExpoScreenTimeModule.swift / ExpoScreenTimeModule.kt). Web has no
// native module at all.
const NativeReportView =
  Platform.OS === "ios" || Platform.OS === "android"
    ? requireNativeView<NativeReportViewProps>(
        "ExpoScreenTime",
        "ExpoScreenTimeReportView",
      )
    : null;

// A build either renders reports or not; the native read (a file probe on iOS)
// is taken once.
let supportedInThisBuild: boolean | null = null;
function usageReportsSupported(): boolean {
  if (supportedInThisBuild === null) {
    supportedInThisBuild = getCapabilities().usageReports === true;
  }
  return supportedInThisBuild;
}

/**
 * DeviceActivityReport renders a JS-authored template against the device's
 * usage data, natively: inside Apple's sandboxed report extension on iOS,
 * from UsageEvents by the module's renderer on Android. The layout comes
 * from `Report.*` children; the values never reach JavaScript.
 *
 * Where `getCapabilities().usageReports` is `false` (a build without the
 * iOS report extension, or an Android build whose `android.features` leaves
 * `usageReports` out) it renders a styled fallback and writes no template.
 *
 * @example
 * ```tsx
 * <DeviceActivityReport filterSegment="daily" style={{ height: 320 }}>
 *   <Report.VStack spacing={12} padding={16}>
 *     <Report.Text bind="totalDuration" durationStyle="abbreviated" size={34} weight="bold" />
 *     <Report.Chart type="area" bind="hourly" accentColor="#FF6B00" gradient height={100} />
 *   </Report.VStack>
 * </DeviceActivityReport>
 * ```
 */
export function DeviceActivityReport({
  filterSegment = "daily",
  filterDate,
  style,
  children,
}: DeviceActivityReportProps) {
  // Default "today" is captured once per mount — a fresh Date.now() every
  // render would change the native prop each render and churn the report view.
  const [mountedAt] = useState(Date.now);
  // Per-instance channel owner: identical templates on two mounted reports
  // must not release each other's claim.
  const templateOwner = useRef({}).current;

  // Whether this build renders reports: the fallback must never call into a
  // template slot the build does not have (it rejects UNSUPPORTED).
  const supported = NativeReportView != null && usageReportsSupported();

  // Serialize the template every render (trees are tiny). Template errors
  // throw here on purpose — fail fast in dev instead of silently natively.
  const templateJson =
    supported && children != null
      ? JSON.stringify(serializeTemplate(children as ReactElement))
      : null;

  // Write during render (idempotent per owner+json): the native view below is
  // committed with this template's hash in the same frame, and the native
  // side may read the slot as soon as that identity changes — a post-commit
  // effect would race it and briefly expose an empty/stale template.
  if (templateJson != null) {
    acquireTemplate(templateOwner, templateJson);
  }

  useEffect(() => {
    if (!supported) return;
    return () => {
      // Release on unmount (and when children are removed): restores the most
      // recent surviving templated report, or clears the channel.
      releaseTemplate(templateOwner);
    };
  }, [supported, templateOwner]);

  useEffect(() => {
    // Children removed while mounted → give up the claim.
    if (supported && templateJson == null) {
      releaseTemplate(templateOwner);
    }
  }, [supported, templateJson, templateOwner]);

  // Fallback where the build cannot render reports (and on web).
  if (!supported || !NativeReportView) {
    return (
      <View style={[styles.fallback, style]}>
        <Text style={styles.fallbackText}>
          Usage reports are not available in this build
        </Text>
      </View>
    );
  }

  const dateTimestamp = filterDate ? filterDate.getTime() : mountedAt;

  return (
    <NativeReportView
      filterSegment={filterSegment}
      filterDate={dateTimestamp}
      templateHash={
        templateJson != null ? hashTemplate(templateJson) : undefined
      }
      style={style}
    />
  );
}

const styles = StyleSheet.create({
  fallback: {
    justifyContent: "center",
    alignItems: "center",
    padding: 20,
    backgroundColor: "#f5f5f5",
    borderRadius: 12,
  },
  fallbackText: {
    color: "#666",
    textAlign: "center",
  },
});

export default DeviceActivityReport;
