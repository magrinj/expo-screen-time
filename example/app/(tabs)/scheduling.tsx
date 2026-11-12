import { Ionicons } from "@expo/vector-icons";
import ExpoScreenTime, {
  MonitoringConfiguration,
  ShieldConfig,
  ShieldAction,
  ShieldActionConfig,
  MonitorEvent,
  getSelectionMetadata,
  useMonitorEvents,
} from "expo-screen-time";
import React, { useState, useEffect, useCallback } from "react";
import {
  Alert,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  Switch,
  RefreshControl,
} from "react-native";

import { SELECTION_ID } from "../_layout";
import {
  ActionButton,
  Card,
  colors,
  EmptyState,
  Row,
  SectionHeader,
  Separator,
} from "../../components/ui";

// openApp posts a notification on iOS (getCapabilities().shieldOpenApp).
const SHIELD_ACTION_CHOICES = ["close", "snooze", "unblock", "openApp"] as const;
type ShieldActionChoice = (typeof SHIELD_ACTION_CHOICES)[number];

type TimePickerProps = {
  label: string;
  hour: number;
  minute: number;
  onChangeHour: (hour: number) => void;
  onChangeMinute: (minute: number) => void;
};

function TimePicker({ label, hour, minute, onChangeHour, onChangeMinute }: TimePickerProps) {
  return (
    <View style={styles.timePicker}>
      <Text style={styles.timeLabel}>{label}</Text>
      <View style={styles.timeInputs}>
        <TextInput
          style={styles.timeInput}
          value={hour.toString().padStart(2, "0")}
          // The last two digits typed win, so a value can be replaced by typing
          // over it (a keyboard, or an automation driver that appends).
          onChangeText={(text) => {
            const num = parseInt(text.replace(/\D/g, "").slice(-2), 10);
            if (!isNaN(num) && num >= 0 && num <= 23) onChangeHour(num);
          }}
          keyboardType="number-pad"
          selectTextOnFocus
          placeholder="HH"
        />
        <Text style={styles.timeSeparator}>:</Text>
        <TextInput
          style={styles.timeInput}
          value={minute.toString().padStart(2, "0")}
          onChangeText={(text) => {
            const num = parseInt(text.replace(/\D/g, "").slice(-2), 10);
            if (!isNaN(num) && num >= 0 && num <= 59) onChangeMinute(num);
          }}
          keyboardType="number-pad"
          selectTextOnFocus
          placeholder="MM"
        />
      </View>
    </View>
  );
}

export default function SchedulingScreen() {
  // Schedule state
  const [activityName, setActivityName] = useState("focusTime");
  const [startHour, setStartHour] = useState(9);
  const [startMinute, setStartMinute] = useState(0);
  const [endHour, setEndHour] = useState(17);
  const [endMinute, setEndMinute] = useState(0);
  const [repeats, setRepeats] = useState(true);
  // Optional: weekdays (1 = Sunday … 7 = Saturday, comma-separated) and one
  // usage threshold over the same selection, with blockOnThreshold.
  // The selection the schedule shields (a stored selection id) and whether it
  // shields at all: off, the activity only meters its threshold.
  const [selectionId, setSelectionId] = useState(SELECTION_ID);
  const [blocksSelection, setBlocksSelection] = useState(true);
  const [weekdaysText, setWeekdaysText] = useState("");
  const [thresholdText, setThresholdText] = useState("");
  const [blockOnThreshold, setBlockOnThreshold] = useState(true);
  // Shield config scope: the default (manual block, fallback) or this activity's.
  const [shieldForActivity, setShieldForActivity] = useState(false);

  // Selection metadata (loaded from native store)
  const [totalSelected, setTotalSelected] = useState(0);

  // Monitoring state
  const [monitoredActivities, setMonitoredActivities] = useState<string[]>([]);
  const [events, setEvents] = useState<MonitorEvent[]>([]);
  // Live stream while this screen is mounted (the hook keeps newest first).
  const { events: liveEvents, isListening } = useMonitorEvents();
  const [refreshing, setRefreshing] = useState(false);

  // Shield config state
  const [shieldTitle, setShieldTitle] = useState("App Blocked");
  const [shieldSubtitle, setShieldSubtitle] = useState("This app is restricted");
  const [shieldBgColor, setShieldBgColor] = useState("#1a1a2e");
  const [shieldButtonLabel, setShieldButtonLabel] = useState("OK");
  const [shieldButtonColor, setShieldButtonColor] = useState("#4a4ae8");

  // Shield actions state
  const [primaryAction, setPrimaryAction] = useState<ShieldActionChoice>("close");
  const [secondaryAction, setSecondaryAction] = useState<ShieldActionChoice>("close");
  const [snoozeDuration, setSnoozeDuration] = useState(5); // minutes

  const loadData = useCallback(async () => {
    try {
      const activities = await ExpoScreenTime.getMonitoredActivities();
      setMonitoredActivities(activities);

      const eventHistory = await ExpoScreenTime.getMonitorEvents(undefined, 50);
      setEvents(eventHistory);

      // Check selection metadata from native store
      const metadata = await getSelectionMetadata(SELECTION_ID);
      if (metadata) {
        setTotalSelected(
          metadata.applicationCount + metadata.categoryCount + metadata.webDomainCount
        );
      }
    } catch (error) {
      console.error("Failed to load data:", error);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  }, [loadData]);

  const handleStartMonitoring = async () => {
    if (totalSelected === 0) {
      Alert.alert("No Selection", "Please select apps to block first on the Blocking tab");
      return;
    }

    const weekdays = weekdaysText
      .split(",")
      .map((part) => parseInt(part.trim(), 10))
      .filter((day) => !isNaN(day));
    const thresholdSeconds = parseInt(thresholdText, 10);
    const config: MonitoringConfiguration = {
      activityName,
      schedule: {
        intervalStart: { hour: startHour, minute: startMinute },
        intervalEnd: { hour: endHour, minute: endMinute },
        repeats,
      },
      ...(blocksSelection ? { selectionId } : {}),
      ...(weekdays.length > 0 ? { weekdays } : {}),
      ...(!isNaN(thresholdSeconds) && thresholdSeconds > 0
        ? {
            events: [
              {
                eventName: "usageLimit",
                selectionId,
                threshold: thresholdSeconds,
                includesPastActivity: false,
                blockOnThreshold,
              },
            ],
          }
        : {}),
    };

    try {
      await ExpoScreenTime.startMonitoring(config);
      Alert.alert("Success", `Monitoring "${activityName}" started!\nBlocking from ${startHour}:${startMinute.toString().padStart(2, "0")} to ${endHour}:${endMinute.toString().padStart(2, "0")}`);
      loadData();
    } catch (error: any) {
      Alert.alert("Error", error.message || "Failed to start monitoring");
    }
  };

  const handleStopMonitoring = async (name: string) => {
    try {
      await ExpoScreenTime.stopMonitoring(name);
      Alert.alert("Success", `Stopped monitoring "${name}"`);
      loadData();
    } catch (error: any) {
      Alert.alert("Error", error.message || "Failed to stop monitoring");
    }
  };

  const handleStopAll = async () => {
    Alert.alert("Stop All Monitoring", "Are you sure?", [
      { text: "Cancel", style: "cancel" },
      {
        text: "Stop All",
        style: "destructive",
        onPress: async () => {
          await ExpoScreenTime.stopAllMonitoring();
          Alert.alert("Success", "All monitoring stopped");
          loadData();
        },
      },
    ]);
  };

  const handleApplyShieldConfig = () => {
    const config: ShieldConfig = {
      title: { text: shieldTitle },
      subtitle: { text: shieldSubtitle },
      backgroundColor: shieldBgColor,
      primaryButton: { text: shieldButtonLabel, backgroundColor: shieldButtonColor },
      secondaryButton: { text: "Snooze" },
    };

    if (shieldForActivity) {
      ExpoScreenTime.setShieldConfigForActivity(activityName, config);
      Alert.alert("Success", `Shield configuration applied to "${activityName}"!\nIts schedule and thresholds draw this shield; the manual block keeps the default.`);
    } else {
      ExpoScreenTime.setShieldConfig(config);
      Alert.alert("Success", "Shield configuration applied!\nBlock an app to see the custom shield.");
    }
  };

  const toShieldAction = (choice: ShieldActionChoice): ShieldAction => {
    if (choice === "snooze") return { type: "snooze", duration: snoozeDuration * 60 };
    if (choice === "openApp") return { type: "openApp" };
    return choice;
  };

  const handleApplyShieldActions = () => {
    const config: ShieldActionConfig = {
      primaryAction: toShieldAction(primaryAction),
      secondaryAction: toShieldAction(secondaryAction),
    };

    ExpoScreenTime.setShieldActions(config);
    Alert.alert("Success", "Shield actions configured!");
  };

  const handleDebugShield = async () => {
    try {
      const result = await ExpoScreenTime.debugShieldConfig();
      Alert.alert("Debug Shield Config", JSON.stringify(result, null, 2));
    } catch (e: any) {
      Alert.alert("Debug Error", e.message);
    }
  };

  const handleClearEvents = async () => {
    await ExpoScreenTime.clearMonitorEvents();
    setEvents([]);
    Alert.alert("Success", "Event history cleared");
  };

  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString();
  };

  const formatDate = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleDateString();
  };

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      refreshControl={
        <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
      }
    >
      {/* Schedule Configuration */}
      <SectionHeader title="Schedule Configuration" />
      <Card>
        <View style={styles.inputRow}>
          <Text style={styles.inputLabel}>Activity Name</Text>
          <TextInput
            style={styles.textInput}
            value={activityName}
            onChangeText={setActivityName}
            placeholder="e.g., focusTime"
          />
        </View>
        <Separator />
        <TimePicker
          label="Start Time"
          hour={startHour}
          minute={startMinute}
          onChangeHour={setStartHour}
          onChangeMinute={setStartMinute}
        />
        <Separator />
        <TimePicker
          label="End Time"
          hour={endHour}
          minute={endMinute}
          onChangeHour={setEndHour}
          onChangeMinute={setEndMinute}
        />
        <Separator />
        <View style={styles.switchRow}>
          <Text style={styles.inputLabel}>Repeat Daily</Text>
          <Switch value={repeats} onValueChange={setRepeats} />
        </View>
        <Separator />
        <View style={styles.inputRow}>
          <Text style={styles.inputLabel}>Selection</Text>
          <TextInput
            style={styles.textInput}
            value={selectionId}
            onChangeText={setSelectionId}
            placeholder={SELECTION_ID}
            autoCapitalize="none"
          />
        </View>
        <View style={styles.switchRow}>
          <Text style={styles.inputLabel}>Block during interval</Text>
          <Switch value={blocksSelection} onValueChange={setBlocksSelection} />
        </View>
        <Separator />
        <View style={styles.inputRow}>
          <Text style={styles.inputLabel}>Weekdays (1=Sun … 7=Sat)</Text>
          <TextInput
            style={styles.textInput}
            value={weekdaysText}
            onChangeText={setWeekdaysText}
            placeholder="every day"
            keyboardType="numbers-and-punctuation"
          />
        </View>
        <Separator />
        <View style={styles.inputRow}>
          <Text style={styles.inputLabel}>Usage threshold (s)</Text>
          <TextInput
            style={styles.textInput}
            value={thresholdText}
            onChangeText={setThresholdText}
            placeholder="none"
            keyboardType="number-pad"
          />
        </View>
        <View style={styles.switchRow}>
          <Text style={styles.inputLabel}>Block on threshold</Text>
          <Switch value={blockOnThreshold} onValueChange={setBlockOnThreshold} />
        </View>
      </Card>

      <ActionButton
        title={`Start Monitoring (${totalSelected} items)`}
        icon="time"
        color={colors.primary}
        onPress={handleStartMonitoring}
        disabled={totalSelected === 0}
      />

      {/* Active Monitoring */}
      <SectionHeader title="Active Monitoring" />
      <Card>
        {monitoredActivities.length > 0 ? (
          monitoredActivities.map((activity, index) => (
            <React.Fragment key={activity}>
              {index > 0 && <Separator />}
              <Row
                icon="timer"
                iconColor={colors.success}
                title={activity}
                subtitle="Active"
                showChevron={false}
                onPress={() => handleStopMonitoring(activity)}
                rightElement={
                  <Ionicons name="close-circle" size={24} color={colors.danger} />
                }
              />
            </React.Fragment>
          ))
        ) : (
          <EmptyState
            icon="timer-outline"
            title="No Active Schedules"
            subtitle="Configure a schedule above to start"
          />
        )}
      </Card>

      {monitoredActivities.length > 0 && (
        <ActionButton
          title="Stop All Monitoring"
          icon="stop-circle"
          color={colors.danger}
          onPress={handleStopAll}
        />
      )}

      {/* Shield Configuration */}
      <SectionHeader title="Shield Appearance" />
      <Card>
        <View style={styles.inputRow}>
          <Text style={styles.inputLabel}>Title</Text>
          <TextInput
            style={styles.textInput}
            value={shieldTitle}
            onChangeText={setShieldTitle}
            placeholder="App Blocked"
          />
        </View>
        <Separator />
        <View style={styles.inputRow}>
          <Text style={styles.inputLabel}>Subtitle</Text>
          <TextInput
            style={styles.textInput}
            value={shieldSubtitle}
            onChangeText={setShieldSubtitle}
            placeholder="This app is restricted"
          />
        </View>
        <Separator />
        <View style={styles.inputRow}>
          <Text style={styles.inputLabel}>Background</Text>
          <TextInput
            style={[styles.textInput, styles.colorInput]}
            value={shieldBgColor}
            onChangeText={setShieldBgColor}
            placeholder="#1a1a2e"
          />
          <View style={[styles.colorPreview, { backgroundColor: shieldBgColor }]} />
        </View>
        <Separator />
        <View style={styles.inputRow}>
          <Text style={styles.inputLabel}>Button Label</Text>
          <TextInput
            style={styles.textInput}
            value={shieldButtonLabel}
            onChangeText={setShieldButtonLabel}
            placeholder="OK"
          />
        </View>
        <Separator />
        <View style={styles.inputRow}>
          <Text style={styles.inputLabel}>Button Color</Text>
          <TextInput
            style={[styles.textInput, styles.colorInput]}
            value={shieldButtonColor}
            onChangeText={setShieldButtonColor}
            placeholder="#4a4ae8"
          />
          <View style={[styles.colorPreview, { backgroundColor: shieldButtonColor }]} />
        </View>
        <Separator />
        <View style={styles.switchRow}>
          <Text style={styles.inputLabel}>For this activity only</Text>
          <Switch value={shieldForActivity} onValueChange={setShieldForActivity} />
        </View>
      </Card>

      <ActionButton
        title={shieldForActivity ? `Apply Shield Configuration to "${activityName}"` : "Apply Shield Configuration"}
        icon="color-palette"
        color={colors.warning}
        onPress={handleApplyShieldConfig}
      />
      <ActionButton
        title="Debug Shield Config"
        icon="bug"
        color={colors.textTertiary}
        onPress={handleDebugShield}
      />

      {/* Shield Actions */}
      <SectionHeader title="Shield Button Actions" />
      <Card>
        <Row
          icon="hand-left"
          iconColor={colors.primary}
          title="Primary Button"
          subtitle={primaryAction}
          showChevron={false}
          rightElement={
            <View style={styles.actionPicker}>
              {SHIELD_ACTION_CHOICES.map((action) => (
                <Text
                  key={action}
                  style={[
                    styles.actionOption,
                    primaryAction === action && styles.actionOptionActive,
                  ]}
                  onPress={() => setPrimaryAction(action)}
                >
                  {action}
                </Text>
              ))}
            </View>
          }
        />
        <Separator />
        <Row
          icon="hand-right"
          iconColor={colors.textSecondary}
          title="Secondary Button"
          subtitle={secondaryAction}
          showChevron={false}
          rightElement={
            <View style={styles.actionPicker}>
              {SHIELD_ACTION_CHOICES.map((action) => (
                <Text
                  key={action}
                  style={[
                    styles.actionOption,
                    secondaryAction === action && styles.actionOptionActive,
                  ]}
                  onPress={() => setSecondaryAction(action)}
                >
                  {action}
                </Text>
              ))}
            </View>
          }
        />
        <Separator />
        <View style={styles.inputRow}>
          <Text style={styles.inputLabel}>Snooze Duration (min)</Text>
          <TextInput
            style={styles.textInput}
            value={snoozeDuration.toString()}
            onChangeText={(text) => {
              const num = parseInt(text, 10);
              if (!isNaN(num) && num > 0) setSnoozeDuration(num);
            }}
            keyboardType="number-pad"
            placeholder="5"
          />
        </View>
      </Card>

      <ActionButton
        title="Apply Shield Actions"
        icon="flash"
        color={colors.success}
        onPress={handleApplyShieldActions}
      />

      {/* Live Events: what useMonitorEvents receives, in arrival order */}
      <SectionHeader
        title={isListening ? "Live Events" : "Live Events (not listening)"}
      />
      <Card>
        {liveEvents.length > 0 ? (
          [...liveEvents].reverse().map((event, index) => (
            <React.Fragment key={`${event.timestamp}-${index}`}>
              {index > 0 && <Separator />}
              <View style={styles.eventRow} testID={`live-event-${index}`}>
                <View style={styles.eventContent}>
                  <Text style={styles.eventType}>
                    {index + 1}. {event.eventType}
                    {event.eventName ? ` · ${event.eventName}` : ""}
                  </Text>
                  <Text style={styles.eventActivity}>{event.activityName}</Text>
                  <Text style={styles.eventTime}>
                    {formatTime(event.timestamp)}
                  </Text>
                </View>
              </View>
            </React.Fragment>
          ))
        ) : (
          <EmptyState
            icon="pulse-outline"
            title="Listening"
            subtitle="Events appear here as they are emitted, oldest first"
          />
        )}
      </Card>

      {/* Event History */}
      <SectionHeader
        title="Event History"
        rightElement={
          events.length > 0 ? (
            <Text style={styles.clearButton} onPress={handleClearEvents}>
              Clear
            </Text>
          ) : undefined
        }
      />
      <Card>
        {events.length > 0 ? (
          events.slice(0, 10).map((event, index) => (
            <React.Fragment key={`${event.timestamp}-${index}`}>
              {index > 0 && <Separator />}
              <View style={styles.eventRow}>
                <View style={styles.eventIcon}>
                  <Ionicons
                    name={
                      event.eventType === "intervalDidStart"
                        ? "play-circle"
                        : event.eventType === "intervalDidEnd"
                        ? "stop-circle"
                        : "alert-circle"
                    }
                    size={24}
                    color={
                      event.eventType === "intervalDidStart"
                        ? colors.success
                        : event.eventType === "intervalDidEnd"
                        ? colors.danger
                        : colors.warning
                    }
                  />
                </View>
                <View style={styles.eventContent}>
                  <Text style={styles.eventType}>{event.eventType}</Text>
                  <Text style={styles.eventActivity}>{event.activityName}</Text>
                  <Text style={styles.eventTime}>
                    {formatDate(event.timestamp)} {formatTime(event.timestamp)}
                  </Text>
                </View>
              </View>
            </React.Fragment>
          ))
        ) : (
          <EmptyState
            icon="list-outline"
            title="No Events Yet"
            subtitle="Events will appear when schedules start/end"
          />
        )}
      </Card>

      <View style={styles.footer} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  content: {
    paddingBottom: 100,
  },
  inputRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  inputLabel: {
    flex: 1,
    fontSize: 16,
    color: colors.text,
  },
  textInput: {
    flex: 1,
    fontSize: 16,
    color: colors.text,
    textAlign: "right",
    padding: 8,
    backgroundColor: colors.background,
    borderRadius: 8,
    marginLeft: 12,
  },
  colorInput: {
    flex: 0.6,
  },
  colorPreview: {
    width: 32,
    height: 32,
    borderRadius: 8,
    marginLeft: 8,
    borderWidth: 1,
    borderColor: colors.separator,
  },
  switchRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  timePicker: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  timeLabel: {
    fontSize: 16,
    color: colors.text,
  },
  timeInputs: {
    flexDirection: "row",
    alignItems: "center",
  },
  timeInput: {
    width: 48,
    fontSize: 20,
    fontWeight: "600",
    color: colors.text,
    textAlign: "center",
    padding: 8,
    backgroundColor: colors.background,
    borderRadius: 8,
  },
  timeSeparator: {
    fontSize: 20,
    fontWeight: "600",
    color: colors.text,
    marginHorizontal: 4,
  },
  actionPicker: {
    flexDirection: "row",
    gap: 8,
  },
  actionOption: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    backgroundColor: colors.background,
    fontSize: 12,
    color: colors.textSecondary,
    overflow: "hidden",
  },
  actionOptionActive: {
    backgroundColor: colors.primary,
    color: "#FFFFFF",
  },
  eventRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  eventIcon: {
    marginRight: 12,
  },
  eventContent: {
    flex: 1,
  },
  eventType: {
    fontSize: 14,
    fontWeight: "600",
    color: colors.text,
  },
  eventActivity: {
    fontSize: 12,
    color: colors.textSecondary,
    marginTop: 2,
  },
  eventTime: {
    fontSize: 11,
    color: colors.textTertiary,
    marginTop: 2,
  },
  clearButton: {
    fontSize: 14,
    color: colors.danger,
    fontWeight: "500",
  },
  footer: {
    height: 20,
  },
});
