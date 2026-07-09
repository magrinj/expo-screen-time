import { requireNativeView } from "expo";
import { useEffect, useRef, type ReactElement, type ReactNode } from "react";
import {
  Platform,
  type StyleProp,
  View,
  type ViewStyle,
  Text,
  StyleSheet,
} from "react-native";

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
 * @platform ios
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
   * serialized (never rendered by React) and shipped to the report extension
   * via the App Group, where values are resolved inside Apple's sandbox.
   *
   * Omit children only when pairing with the imperative
   * `setReportTemplate(element)` — with no template at all, the extension
   * renders a "No custom template set" placeholder.
   *
   * One template is active at a time (App Group is a single channel) — the
   * last mounted report with children wins.
   *
   * **Sizing:** the report renders in a sandboxed remote view that cannot
   * report its content height back to the app — the template is clipped to
   * this component's `style`. Size the container to fit your template (e.g.
   * a fixed `height`).
   */
  children?: ReactNode;
}

// Native props interface for the view
interface NativeReportViewProps {
  filterSegment: string;
  filterDate: number;
  /** Hash of the active template — changes force the extension to re-read it
   * (applied as the SwiftUI view identity). */
  templateHash?: string;
  style?: StyleProp<ViewStyle>;
}

// Native view for iOS 16+ using Expo's new view API
// Registered as a named view on the ExpoScreenTime module (see ExpoScreenTimeModule.swift)
const NativeReportView =
  Platform.OS === "ios"
    ? requireNativeView<NativeReportViewProps>(
        "ExpoScreenTime",
        "ExpoScreenTimeReportView",
      )
    : null;

/**
 * DeviceActivityReport renders a JS-authored template against iOS Screen Time
 * usage data, inside Apple's sandboxed report extension. The layout comes from
 * `Report.*` children; the values never reach JavaScript.
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
 *
 * @platform ios (iOS 16.0+)
 */
export function DeviceActivityReport({
  filterSegment = "daily",
  filterDate,
  style,
  children,
}: DeviceActivityReportProps) {
  // Default "today" is captured once per mount — a fresh Date.now() every
  // render would change the native prop each render and churn the report view.
  const mountedAt = useRef(Date.now());
  // Per-instance channel owner: identical templates on two mounted reports
  // must not release each other's claim.
  const templateOwner = useRef({}).current;

  // Serialize the template every render (trees are tiny). Template errors
  // throw here on purpose — fail fast in dev instead of silently in the
  // sandbox.
  const templateJson =
    Platform.OS === "ios" && children != null
      ? JSON.stringify(serializeTemplate(children as ReactElement))
      : null;

  // Write during render (idempotent per owner+json): the native view below is
  // committed with this template's hash in the same frame, and the extension
  // may read the App Group as soon as that identity changes — a post-commit
  // effect would race it and briefly expose an empty/stale template.
  if (templateJson != null) {
    acquireTemplate(templateOwner, templateJson);
  }

  useEffect(() => {
    if (Platform.OS !== "ios") return;
    return () => {
      // Release on unmount (and when children are removed): restores the most
      // recent surviving templated report, or clears the channel.
      releaseTemplate(templateOwner);
    };
  }, [templateOwner]);

  useEffect(() => {
    // Children removed while mounted → give up the claim.
    if (Platform.OS === "ios" && templateJson == null) {
      releaseTemplate(templateOwner);
    }
  }, [templateJson, templateOwner]);

  // Fallback for non-iOS platforms
  if (Platform.OS !== "ios" || !NativeReportView) {
    return (
      <View style={[styles.fallback, style]}>
        <Text style={styles.fallbackText}>
          DeviceActivityReport is only available on iOS 16+
        </Text>
      </View>
    );
  }

  const dateTimestamp = filterDate ? filterDate.getTime() : mountedAt.current;

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
