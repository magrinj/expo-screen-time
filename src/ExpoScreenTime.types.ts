import type { PermissionResponse } from "expo-modules-core";
import type { StyleProp, ViewStyle } from "react-native";

// ============================================================================
// PERMISSIONS
// ============================================================================

/** One thing the user must allow. iOS has one step: the Screen Time prompt. */
export type PermissionStepId = "screenTime";

export type PermissionStep = {
  id: PermissionStepId;
  /**
   * `restricted`: the OS refuses to let this user grant it (iOS
   * `FamilyControlsError.restricted`: Screen Time restrictions, a managed
   * device). `unknown`: the status cannot be read back.
   */
  status: "granted" | "denied" | "undetermined" | "restricted" | "unknown";
  /** `true`: needed by the module. `false`: recommended for reliability. */
  required: boolean;
  /** `prompt`: a system dialog. `settings`: a settings screen. `guide`: a vendor screen. */
  kind: "prompt" | "settings" | "guide";
  /**
   * Why the last request failed, verbatim from the OS: on iOS the
   * `FamilyControlsError` case name (`restricted`, `invalidAccountType`,
   * `authorizationCanceled`, `networkError`, …) and its description. Absent
   * when the last request succeeded or once access is granted. Kept for the
   * app's lifetime only.
   */
  error?: { code: string; message: string };
};

/**
 * Expo's `PermissionResponse` plus the steps behind it:
 * - `granted` is `true` when every `required` step is `granted`;
 * - a `restricted` step reports `status: "denied"` and `canAskAgain: false`.
 */
export type ScreenTimePermissionResponse = PermissionResponse & {
  steps: PermissionStep[];
};

export type ScreenTimePermissionOptions = {
  /**
   * Who authorizes. `individual` (default): the user approves for this
   * device. `child`: Family Sharing authorization of a child account, approved
   * by a parent; requests `.child` on iOS, and fails with the step error
   * `invalidAccountType` on an account that is not a child in a family.
   * Only read when requesting.
   * @platform ios
   */
  member?: "individual" | "child";
};

// ============================================================================
// TOKEN & SELECTION TYPES
// ============================================================================

/**
 * Reference to a native token, used to address it from JS (labels, removal).
 * The handle is derived from the encoded token so it's stable across launches.
 * Apple's tokens are opaque blobs — undecodable even natively — but treat the
 * handle itself as opaque too: never parse or construct one in JS.
 * @platform ios
 */
export type TokenHandle = {
  /** Opaque, stable identifier for the token */
  handle: string;
  /** Type of token */
  type: "application" | "category" | "webDomain";
};

/**
 * Metadata about a selection (counts only, no token data)
 * @platform ios
 */
export type SelectionMetadata = {
  applicationCount: number;
  categoryCount: number;
  webDomainCount: number;
};

// ============================================================================
// COMPONENT PROPS
// ============================================================================

/**
 * Event payload from ActivityPicker selection change
 * @platform ios
 */
export type OnSelectionChangeEvent = {
  metadata: SelectionMetadata;
  tokens: TokenHandle[];
};

export type ExpoScreenTimeActivityPickerProps = {
  style?: StyleProp<ViewStyle>;
  /** ID of the selection in the native SelectionStore */
  selectionId: string;
  headerText?: string;
  footerText?: string;
  onSelectionChange?: (event: { nativeEvent: OnSelectionChangeEvent }) => void;
  onOpen?: () => void;
  onClose?: () => void;
};

/**
 * Display mode for token labels
 * @platform ios
 */
export type DisplayMode = "icon-only" | "title-only" | "title-and-icon";

/**
 * Props for TokenLabel component
 * @platform ios
 */
export type TokenLabelProps = {
  style?: StyleProp<ViewStyle>;
  /** ID of the selection containing this token */
  selectionId: string;
  /** Handle identifying the specific token */
  handle: string;
  /** Type of token: determines which native Label to render */
  tokenType: "application" | "category" | "webDomain";
  /** Display mode */
  displayMode?: DisplayMode;
};

export type ExpoScreenTimeModuleEvents = {
  onMonitorEvent: (event: OnMonitorEventPayload) => void;
};

// ============================================================================
// SCHEDULING TYPES
// ============================================================================

export type DateComponents = {
  hour: number;
  minute: number;
  second?: number;
  weekday?: number;
};

export type DeviceActivitySchedule = {
  intervalStart: DateComponents;
  intervalEnd: DateComponents;
  repeats: boolean;
  warningTime?: number;
};

export type DeviceActivityEventThreshold = {
  eventName: string;
  /**
   * Selection whose usage is counted — references SelectionStore by ID. With
   * `blockOnThreshold`, it is also the selection that gets shielded.
   */
  selectionId: string;
  /** Seconds of usage within the interval. */
  threshold: number;
  includesPastActivity?: boolean;
  /**
   * Shield `selectionId` from the moment the threshold is reached until the
   * interval ends. The shield is one block source among the others (the
   * activity's own selection, the manual block, other schedules): it goes
   * away with the interval and leaves the rest in force.
   */
  blockOnThreshold?: boolean;
};

export type MonitoringConfiguration = {
  /**
   * Stable identity of the activity: events, `getMonitoredActivities`,
   * per-activity shield configs and actions always use this name. One
   * activity costs one of the 20 DeviceActivity slots, `weekdays` included.
   * Must not be empty or contain `__expo_st_gen_` (reserved). Calling
   * `startMonitoring` again with the same name updates the activity in
   * place; an interval in progress keeps its shields.
   */
  activityName: string;
  /**
   * `intervalEnd` at or before `intervalStart` crosses midnight. If the
   * current time is inside the interval, the block is applied synchronously
   * and `intervalDidStart` is recorded at once (the system callback is
   * asynchronous).
   */
  schedule: DeviceActivitySchedule;
  events?: DeviceActivityEventThreshold[];
  /** Selection to block while the interval is active — references SelectionStore by ID */
  selectionId?: string;
  /**
   * Restrict the schedule to these weekdays (1 = Sunday … 7 = Saturday). The
   * activity is still registered once; occurrences on other days are ignored
   * (no block, no events). An occurrence that crosses midnight belongs to the
   * weekday on which it starts.
   */
  weekdays?: number[];
};

// ============================================================================
// SHIELD CONFIGURATION TYPES
// ============================================================================

export type UIBlurEffectStyle =
  | "extraLight"
  | "light"
  | "dark"
  | "regular"
  | "prominent"
  | "systemUltraThinMaterial"
  | "systemThinMaterial"
  | "systemMaterial"
  | "systemThickMaterial"
  | "systemChromeMaterial"
  | "systemUltraThinMaterialLight"
  | "systemThinMaterialLight"
  | "systemMaterialLight"
  | "systemThickMaterialLight"
  | "systemChromeMaterialLight"
  | "systemUltraThinMaterialDark"
  | "systemThinMaterialDark"
  | "systemMaterialDark"
  | "systemThickMaterialDark"
  | "systemChromeMaterialDark";

export type ShieldConfig = {
  title?: { text: string; color?: string };
  subtitle?: { text: string; color?: string };
  icon?: string;
  backgroundColor?: string;
  backgroundBlurStyle?: UIBlurEffectStyle;
  primaryButton?: { text: string; color?: string; backgroundColor?: string };
  secondaryButton?: { text: string; color?: string };
};

export type ShieldActionType = "close" | "unblock" | "defer";

export type ShieldActionConfig = {
  primaryAction: ShieldActionType | { type: "snooze"; duration: number };
  secondaryAction?: ShieldActionType | { type: "snooze"; duration: number };
};

// ============================================================================
// MONITOR EVENT TYPES
// ============================================================================

export type MonitorEventType =
  | "intervalDidStart"
  | "intervalDidEnd"
  | "eventDidReachThreshold"
  | "intervalWillEndWarning"
  | "eventWillReachThresholdWarning";

export type MonitorEvent = {
  /** Unique: every event is delivered exactly once, oldest first. */
  id: string;
  activityName: string;
  eventType: MonitorEventType;
  eventName?: string;
  timestamp: number;
};

export type OnMonitorEventPayload = {
  event: MonitorEvent;
};

// ============================================================================
// ERROR TYPES
// ============================================================================

export type MonitoringErrorCode =
  | "EXCESSIVE_ACTIVITIES"
  | "INTERVAL_TOO_SHORT"
  | "INTERVAL_TOO_LONG"
  | "INVALID_DATE_COMPONENTS"
  | "UNAUTHORIZED"
  | "INVALID_CONFIG"
  /**
   * `startMonitoring` failed after the activity's previous registration had
   * to be released (the 20-activity limit), and putting that registration
   * back failed too: the activity is no longer monitored.
   * @platform ios
   */
  | "MONITORING_ROLLBACK_FAILED"
  | "UNSUPPORTED";

// ============================================================================
// TEMPORARY UNLOCK (EARNED TIME)
// ============================================================================

/**
 * Result of `temporaryUnlock`. `budgetSeconds` is the granted usage budget
 * (after the 30s floor); `grantedAt` is Unix milliseconds.
 *
 * There is deliberately no `expiresAt`: the budget is spent by usage, not by
 * the clock, so its end time depends on how the user behaves. Unspent budget
 * expires at midnight.
 */
export type TemporaryUnlockGrant = {
  budgetSeconds: number;
  grantedAt: number;
};

/**
 * One blocked-app attempt the user reacted to on the shield.
 * Returned by `drainShieldInteractions`.
 */
export type ShieldInteraction = {
  /** Which shield button was pressed. */
  button: "primary" | "secondary";
  /** Unix milliseconds. */
  timestamp: number;
};
