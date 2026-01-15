import type { StyleProp, ViewStyle } from "react-native";

// ============================================================================
// TOKEN & SELECTION TYPES
// ============================================================================

/**
 * Lightweight reference to a native token.
 * The actual token data stays on the Swift side.
 * @platform ios
 */
export type TokenHandle = {
  /** Hash-based identifier for the token */
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
  /** Selection to monitor — references SelectionStore by ID */
  selectionId: string;
  threshold: number;
  includesPastActivity?: boolean;
};

export type MonitoringConfiguration = {
  activityName: string;
  schedule: DeviceActivitySchedule;
  events?: DeviceActivityEventThreshold[];
  /** Selection to block when interval starts — references SelectionStore by ID */
  selectionId?: string;
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
  | "UNSUPPORTED";
