import { requireNativeView } from "expo";
import { useEffect } from "react";
import {
  Platform,
  StyleProp,
  View,
  ViewStyle,
  Text,
  StyleSheet,
} from "react-native";

import ExpoScreenTimeModule, { ReportStyle } from "./ExpoScreenTimeModule";

/**
 * Report context determines which report view to display
 */
export type ReportContext = "TotalActivity" | "AppList" | "HourlyChart";

/**
 * Filter segment for time-based filtering
 */
export type FilterSegment = "daily" | "weekly";

/**
 * Props for DeviceActivityReport component
 * @platform ios
 */
export interface DeviceActivityReportProps {
  /** Which report view to display */
  context?: ReportContext;

  /** Time filter segment */
  filterSegment?: FilterSegment;

  /** Date to filter by (defaults to today) */
  filterDate?: Date;

  /** Container layout style */
  style?: StyleProp<ViewStyle>;

  // SwiftUI styling props (passed to extension via App Group)

  /** Accent color for highlights (hex string, e.g., "#FF6B00") */
  accentColor?: string;

  /** Background color (hex string) */
  backgroundColor?: string;

  /** Text color (hex string) */
  textColor?: string;

  /** Show pickup count in reports */
  showPickups?: boolean;

  /** Show top apps section */
  showTopApps?: boolean;

  /** Show usage chart */
  showChart?: boolean;

  /** Chart style */
  chartStyle?: "bar" | "pie";
}

// Native props interface for the view
interface NativeReportViewProps {
  context: string;
  filterSegment: string;
  filterDate: number;
  style?: StyleProp<ViewStyle>;
}

// Native view for iOS 16+ using Expo's new view API
// The view name matches the module name in ExpoScreenTimeReportModule.swift
const NativeReportView =
  Platform.OS === "ios"
    ? requireNativeView<NativeReportViewProps>("ExpoScreenTimeReport")
    : null;

/**
 * DeviceActivityReport displays usage analytics from iOS Screen Time.
 *
 * This component embeds Apple's DeviceActivityReport view, which renders
 * usage data from a sandboxed extension. The data cannot be accessed
 * from JavaScript, but styling can be customized via props.
 *
 * @example
 * ```tsx
 * <DeviceActivityReport
 *   context="TotalActivity"
 *   filterSegment="daily"
 *   accentColor="#FF6B00"
 *   style={{ height: 200 }}
 * />
 * ```
 *
 * @platform ios (iOS 16.0+)
 */
export function DeviceActivityReport({
  context = "TotalActivity",
  filterSegment = "daily",
  filterDate,
  style,
  accentColor,
  backgroundColor,
  textColor,
  showPickups,
  showTopApps,
  showChart,
  chartStyle,
}: DeviceActivityReportProps) {
  // Write styling to App Group for extension to read
  useEffect(() => {
    if (Platform.OS !== "ios") return;

    const styleConfig: ReportStyle = {};

    if (accentColor !== undefined) styleConfig.accentColor = accentColor;
    if (backgroundColor !== undefined)
      styleConfig.backgroundColor = backgroundColor;
    if (textColor !== undefined) styleConfig.textColor = textColor;
    if (showPickups !== undefined) styleConfig.showPickups = showPickups;
    if (showTopApps !== undefined) styleConfig.showTopApps = showTopApps;
    if (showChart !== undefined) styleConfig.showChart = showChart;
    if (chartStyle !== undefined) styleConfig.chartStyle = chartStyle;

    if (Object.keys(styleConfig).length > 0) {
      ExpoScreenTimeModule.setReportStyle(styleConfig);
    }
  }, [
    accentColor,
    backgroundColor,
    textColor,
    showPickups,
    showTopApps,
    showChart,
    chartStyle,
  ]);

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

  const dateTimestamp = filterDate ? filterDate.getTime() : Date.now();

  return (
    <NativeReportView
      context={context}
      filterSegment={filterSegment}
      filterDate={dateTimestamp}
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
