import type { PermissionResponse } from "expo-modules-core";
import type { StyleProp, ViewStyle } from "react-native";

// ============================================================================
// PERMISSIONS
// ============================================================================

/**
 * What the app intends to use. Decides which permission steps are required:
 * on iOS, `shieldOpenApp` adds the `notifications` step (a shield's
 * `{ type: "openApp" }` action reaches the app through a notification the
 * user taps); the other features all rest on the `screenTime` step.
 */
export type ScreenTimeFeature =
  | "blocking"
  | "monitoring"
  | "usageReports"
  | "shieldOpenApp"
  | "appRemovalProtection"
  | "webDomainBlocking";

/**
 * One thing the user must allow. iOS lists `screenTime` (the Family Controls
 * prompt) and, for the `shieldOpenApp` feature, `notifications`. Android
 * lists `overlay` and `usageAccess` (required), then `notifications`,
 * `deviceAdmin`, `batteryOptimization` and, on some makers,
 * `backgroundAutostart` (recommended). Each platform lists only its own
 * steps; the ids exist in one type so shared code can label every step.
 */
export type PermissionStepId =
  /** iOS: Family Controls authorization (system prompt). */
  | "screenTime"
  /** Android: Settings › Usage access. */
  | "usageAccess"
  /** Android: Settings › Display over other apps. */
  | "overlay"
  /** Android: Settings › Accessibility. */
  | "accessibility"
  /** iOS: for `shieldOpenApp`. Android 13+: the protection notice. */
  | "notifications"
  /** Android: the system's battery-optimization list (no permission is requested). */
  | "batteryOptimization"
  /** Android OEMs (Xiaomi, Oppo, Vivo, Huawei, OnePlus…): the vendor autostart screen; its status stays `unknown`. */
  | "backgroundAutostart"
  /** Android: device admin activation, which disables "Force stop" and guards uninstall. */
  | "deviceAdmin";

export type PermissionStep = {
  id: PermissionStepId;
  /**
   * `restricted`: the OS refuses to let this user grant it (iOS
   * `FamilyControlsError.restricted`: Screen Time restrictions, a managed
   * device; Android "restricted settings" for a sideloaded app, or the
   * overlay permission on a low-RAM device). `unknown`: the status cannot
   * be read back (the vendor autostart screen). An Android settings toggle
   * that is off reads `undetermined` until the app has sent the user to
   * that screen once, `denied` after.
   */
  status: "granted" | "denied" | "undetermined" | "restricted" | "unknown";
  /** `true`: needed by the requested features. `false`: recommended for reliability. */
  required: boolean;
  /**
   * `prompt`: a system dialog. `settings`: a settings screen; the request
   * resolves when the user comes back to the app. `guide`: a vendor screen
   * (same), whose status stays `unknown`.
   */
  kind: "prompt" | "settings" | "guide";
  /**
   * Why the last request failed, verbatim from the OS: for `screenTime` the
   * `FamilyControlsError` case name (`restricted`, `invalidAccountType`,
   * `authorizationCanceled`, `networkError`, …) and its description; for
   * `notifications` an error thrown by the request (a refusal is not an
   * error: the step reads `denied`); on Android, a settings screen that
   * could not be opened (`ActivityNotFoundException`). Absent when the last
   * request succeeded or once the step is granted. Kept for the app's
   * lifetime on iOS, across launches on Android.
   */
  error?: { code: string; message: string };
};

/**
 * Expo's `PermissionResponse` plus the ordered steps behind it:
 * - `granted` is `true` when every `required` step is `granted`;
 * - `status` is `granted` if so; else `denied` if a required step is
 *   `denied` or `restricted`; else `undetermined`;
 * - `canAskAgain` is `true` when no required step is `restricted` and every
 *   required step still to grant can show its prompt: Family Controls asks
 *   again after a denial, the notification prompt is shown once (after a
 *   denial only Settings can grant it).
 * iOS reports one required step (`screenTime`), plus `notifications` when
 * `shieldOpenApp` is among the `features`.
 */
export type ScreenTimePermissionResponse = PermissionResponse & {
  steps: PermissionStep[];
};

export type ScreenTimePermissionOptions = {
  /**
   * What the app will use; decides which steps are listed and required.
   * Default `["blocking", "monitoring"]`. Add `shieldOpenApp` to list the
   * `notifications` step.
   */
  features?: ScreenTimeFeature[];
  /**
   * Who authorizes. `individual` (default): the user approves for this
   * device. `child`: Family Sharing authorization of a child account, approved
   * by a parent; requests `.child` on iOS (only read when requesting), and
   * fails with the step error `invalidAccountType` on an account that is not
   * a child in a family. On Android it makes the `notifications` and
   * `deviceAdmin` steps required (Google Play's parental-control rules
   * demand a persistent notice), so it is read when listing the steps too.
   */
  member?: "individual" | "child";
};

// ============================================================================
// CAPABILITIES
// ============================================================================

/** How strongly a restriction holds against a determined user. */
export type Enforcement =
  /** The OS itself refuses the action. */
  | "enforced"
  /**
   * The restriction holds, but the user can lift it through a route the app
   * cannot close (for example by revoking Screen Time access in Settings);
   * `getEnforcementGaps()` reports when that happened.
   */
  | "friction"
  | "none";

/**
 * What this build, on this device, with the authorization last granted, can
 * do. Branch on it instead of `Platform.OS`.
 */
export type ScreenTimeCapabilities = {
  /** Strength of `blockSelection` and schedules. iOS: `enforced` (the OS draws the shield). */
  blocking: Enforcement;
  /** What `<ActivityPicker>` offers and `blockSelection` enforces. */
  selectableKinds: {
    applications: boolean;
    categories: boolean;
    webDomains: boolean;
  };
  /** `blockWebDomains` and co. work (otherwise they throw `UNSUPPORTED`). */
  webDomainBlocking: boolean;
  /**
   * For the authorization last granted: iOS `individual` is `friction` (the
   * user can revoke Screen Time access in Settings, then delete the app);
   * `child` is `enforced` (a parent holds the authorization).
   */
  appRemovalProtection: Enforcement;
  /** Same rule as `appRemovalProtection`, for `denyAppInstallation`. */
  appInstallationBlocking: Enforcement;
  /** `<DeviceActivityReport>` renders data: the report extension is in this build. */
  usageReports: boolean;
  /**
   * How a shield's `{ type: "openApp" }` action reaches the host app: at
   * once, or through a notification the user taps (iOS, which needs the
   * `notifications` permission step, requested with the `shieldOpenApp`
   * feature).
   */
  shieldOpenApp: "direct" | "notification";
  /** `openApplication(handle)` works. iOS cannot launch another app: `false`. */
  openApplication: boolean;
  limits: {
    /**
     * Activities you can monitor at once (19 on both platforms):
     * `startMonitoring` rejects one more with `EXCESSIVE_ACTIVITIES`. iOS has
     * 20 DeviceActivity slots and the temporary-unlock meter holds one (each
     * snooze in progress also holds one until it is spent).
     */
    maxMonitoredActivities: number;
    /** Shortest schedule interval (`INTERVAL_TOO_SHORT` below it). */
    minIntervalSeconds: number;
    /** Longest schedule interval (`INTERVAL_TOO_LONG` above it). */
    maxIntervalSeconds: number;
  };
};

/** A period during which blocks were intended but not enforced. */
export type EnforcementGap = {
  /** Unix ms. */
  from: number;
  /** Unix ms; `null` while the gap is still open. */
  to: number | null;
  /**
   * iOS reports `authorizationRevoked`: Screen Time access was revoked, which
   * lifts every shield. The other reasons are for platforms whose
   * enforcement can be stopped by the user or the system.
   */
  reason:
    | "stoppedByUser"
    | "permissionRevoked"
    | "killedBySystem"
    | "authorizationRevoked"
    | "unknown";
  /**
   * What the gap covers. Absent: every blocked app. `otherProfiles`: only
   * the blocked apps opened from a work profile, Private Space or a clone;
   * the main profile stayed enforced (the accessibility service, which
   * alone sees those profiles, was down while the UsageStats engine ran).
   * Only in builds that opt into the accessibility engine.
   * @platform android
   */
  scope?: "otherProfiles";
};

// ============================================================================
// TOKEN & SELECTION TYPES
// ============================================================================

/**
 * Reference to a native token, used to address it from JS (labels, removal).
 * On iOS the handle is derived from the encoded token; on Android from the
 * package name and a per-install secret. Either way it is stable across
 * launches on one install and opaque: never parse or construct one in JS.
 */
export type TokenHandle = {
  /** Opaque, stable identifier for the token */
  handle: string;
  /** Type of token */
  type: "application" | "category" | "webDomain";
};

/**
 * Metadata about a selection (counts only, no token data)
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
 */
export type DisplayMode = "icon-only" | "title-only" | "title-and-icon";

/**
 * Props for TokenLabel component
 */
export type TokenLabelProps = {
  style?: StyleProp<ViewStyle>;
  /**
   * Selection that holds the token. Optional: a handle is self-describing,
   * so the label resolves it natively on its own — this is how you label a
   * handle you persisted that no longer belongs to a stored selection. When
   * given, the handle is looked up inside that selection and renders the
   * placeholder if it is not part of it.
   */
  selectionId?: string;
  /** Handle identifying the specific token */
  handle: string;
  /**
   * Type of token: determines which native Label to render. Must be the
   * handle's own `TokenHandle.type` — a handle of another type is not
   * detected natively and renders a blank label.
   */
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

/**
 * How a block applies its selection — `blockSelection`'s `mode` option and
 * `MonitoringConfiguration.mode`.
 *
 * - `"blocklist"` (default): shield the selection's apps, categories and
 *   web domains.
 * - `"allowlist"`: shield every app EXCEPT the selection's apps, and the
 *   whole web. The selection may hold **applications only, at least one**:
 *   one with categories or web domains, or an empty one, is rejected with
 *   `INVALID_CONFIG` on both platforms (iOS `ActivityCategoryPolicy.all(except:)`
 *   takes application tokens only). The host app, Phone and Settings are never shielded; the
 *   web is shielded whole because the application policy does not reach
 *   Safari, so Safari cannot be allowed. An allowlist cannot be metered by
 *   `temporaryUnlock` (it rejects `UNSUPPORTED` while one is active).
 */
export type BlockMode = "blocklist" | "allowlist";

export type MonitoringConfiguration = {
  /**
   * Stable identity of the activity: events, `getMonitoredActivities`,
   * per-activity shield configs and actions always use this name. One
   * activity costs one of the `limits.maxMonitoredActivities` slots,
   * `weekdays` included.
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
   * How `selectionId` is applied while the interval is active. Default
   * `"blocklist"`. `"allowlist"` shields every app except the selection's
   * apps (applications only, else `INVALID_CONFIG`; it also requires
   * `selectionId`); `blockOnThreshold` events keep blocklist semantics.
   */
  mode?: BlockMode;
  /**
   * Restrict the schedule to these weekdays (1 = Sunday … 7 = Saturday). The
   * activity is still registered once; occurrences on other days are ignored
   * (no block, no events). An occurrence that crosses midnight belongs to the
   * weekday on which it starts. A one-off schedule (`repeats: false`) covers
   * its next occurrence on a selected weekday (the one in progress when it
   * is on a selected weekday).
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

/** What a shield button does. */
export type ShieldAction =
  /** Dismiss to the Home screen; the block stays. */
  | "close"
  /**
   * Lift the shield on the pressed target in every source shielding it,
   * until a block is applied again.
   */
  | "unblock"
  /** Keep the shield up; the press is still recorded. */
  | "defer"
  /** Lift the pressed target for `duration` seconds of its use (at least 30). */
  | { type: "snooze"; duration: number }
  /**
   * Bring the host app forward; `getCapabilities().shieldOpenApp` says how.
   * iOS cannot launch an app from a shield, so it posts a local
   * notification the user taps (the `notifications` permission step,
   * requested with the `shieldOpenApp` feature); without it the press
   * behaves like `"close"`. The press is recorded either way, so the app,
   * once open, finds the shielded `target` in `drainShieldInteractions()`.
   */
  | { type: "openApp" };

export type ShieldActionConfig = {
  primaryAction: ShieldAction;
  secondaryAction?: ShieldAction;
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
 * Result of `temporaryUnlock`.
 *
 * There is deliberately no `expiresAt`: the budget is spent by usage, not by
 * the clock, so its end time depends on how the user behaves. Unspent budget
 * expires at midnight.
 */
export type TemporaryUnlockGrant = {
  /** Granted usage budget, after the 30s floor. */
  budgetSeconds: number;
  /** Unix milliseconds. */
  grantedAt: number;
  /**
   * How coarsely the budget is metered: consumption is reported in steps of
   * this many seconds of use, so the remaining time moves by this much at a
   * time. iOS: 30 for budgets up to 30 minutes, then `ceil(budget / 60)`
   * (120 for a 2-hour budget). Tell the user "about N minutes" rather than
   * counting down.
   */
  meteringSeconds: number;
  /**
   * The single app, category or web domain this grant lifts (the `target`
   * option, echoed back), or `null` for a whole-block unlock.
   */
  target: TokenHandle | null;
};

/**
 * One press of a shield button, returned by `drainShieldInteractions`.
 * Presses on the SAME target within 2 s collapse into one (a user tapping
 * both buttons of one shield is one blocked attempt); presses on different
 * targets are separate interactions. Two apps shielded through the same
 * category are one target (iOS reports the category).
 * @platform ios
 */
export type ShieldInteraction = {
  /** Which shield button was pressed. */
  button: "primary" | "secondary";
  /** Unix milliseconds. */
  timestamp: number;
  /**
   * What was shielded: the pressed app, web domain or category (an app
   * shielded through its category reports the category). The same handle
   * `getTokens` returns for that token, so render it with `<TokenLabel>`
   * and a selection containing it. `null` when no handle could be derived,
   * and for interactions recorded by versions before this field existed.
   */
  target: TokenHandle | null;
  /**
   * The monitored activity whose block shields the target (a threshold
   * block, else the most recently started schedule covering it, as for the
   * shield config and actions). `null` for the manual block
   * (`blockSelection`), when no active block owns the shield any more, and
   * for interactions recorded before this field.
   */
  activityName: string | null;
};
