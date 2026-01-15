import React, { useState } from "react";
import {
  ScrollView,
  StyleSheet,
  Text,
  View,
  TouchableOpacity,
} from "react-native";
import { DeviceActivityReport, FilterSegment } from "expo-screen-time";

type SegmentOption = "daily" | "weekly";

export default function AnalyticsScreen() {
  const [segment, setSegment] = useState<SegmentOption>("daily");

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Segment Picker */}
      <View style={styles.segmentContainer}>
        <TouchableOpacity
          style={[
            styles.segmentButton,
            segment === "daily" && styles.segmentButtonActive,
          ]}
          onPress={() => setSegment("daily")}
        >
          <Text
            style={[
              styles.segmentText,
              segment === "daily" && styles.segmentTextActive,
            ]}
          >
            Daily
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[
            styles.segmentButton,
            segment === "weekly" && styles.segmentButtonActive,
          ]}
          onPress={() => setSegment("weekly")}
        >
          <Text
            style={[
              styles.segmentText,
              segment === "weekly" && styles.segmentTextActive,
            ]}
          >
            Weekly
          </Text>
        </TouchableOpacity>
      </View>

      {/* Total Activity */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Total Screen Time</Text>
        <View pointerEvents="none">
          <DeviceActivityReport
            context="TotalActivity"
            filterSegment={segment as FilterSegment}
            accentColor="#007AFF"
            style={styles.reportCard}
          />
        </View>
      </View>

      {/* Hourly Chart */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Hourly Usage</Text>
        <View pointerEvents="none">
          <DeviceActivityReport
            context="HourlyChart"
            filterSegment={segment as FilterSegment}
            accentColor="#34C759"
            showChart
            chartStyle="bar"
            style={styles.chartCard}
          />
        </View>
      </View>

      {/* App List */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Apps</Text>
        <View pointerEvents="none">
          <DeviceActivityReport
            context="AppList"
            filterSegment={segment as FilterSegment}
            accentColor="#FF9500"
            showTopApps
            style={styles.listCard}
          />
        </View>
      </View>

      <View style={styles.footer}>
        <Text style={styles.footerText}>
          Data is rendered by iOS DeviceActivityReportExtension.
          {"\n"}Usage data cannot be accessed from JavaScript.
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
    marginBottom: 20,
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
  section: {
    marginBottom: 24,
  },
  sectionTitle: {
    fontSize: 20,
    fontWeight: "600",
    color: "#000",
    marginBottom: 12,
  },
  reportCard: {
    height: 150,
    backgroundColor: "#FFFFFF",
    borderRadius: 12,
    overflow: "hidden",
  },
  chartCard: {
    height: 250,
    backgroundColor: "#FFFFFF",
    borderRadius: 12,
    overflow: "hidden",
  },
  listCard: {
    height: 300,
    backgroundColor: "#FFFFFF",
    borderRadius: 12,
    overflow: "hidden",
  },
  footer: {
    marginTop: 8,
    padding: 16,
  },
  footerText: {
    fontSize: 12,
    color: "#8E8E93",
    textAlign: "center",
    lineHeight: 18,
  },
});
