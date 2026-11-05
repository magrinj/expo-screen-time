import { NativeModule, requireNativeModule } from "expo";

import type {
  BlockMode,
  EnforcementGap,
  ExpoScreenTimeModuleEvents,
  ScreenTimeCapabilities,
  MonitoringConfiguration,
  ShieldConfig,
  ShieldActionConfig,
  SelectionMetadata,
  ShieldInteraction,
  TemporaryUnlockGrant,
  TokenHandle,
  MonitorEvent,
  PermissionStepId,
  ScreenTimePermissionOptions,
  ScreenTimePermissionResponse,
} from "./ExpoScreenTime.types";

declare class ExpoScreenTimeModule extends NativeModule<ExpoScreenTimeModuleEvents> {
  // Permissions
  /**
   * Advances onboarding by ONE step: `step` if given, else the first
   * required step not granted; resolves with the whole response. Never
   * rejects for a refused prompt (the step's `error` says why); rejects with
   * `INVALID_CONFIG` for a step this platform does not have, and with
   * `UNSUPPORTED` before iOS 16.
   */
  requestScreenTimePermissionsAsync(
    options?: ScreenTimePermissionOptions & { step?: PermissionStepId },
  ): Promise<ScreenTimePermissionResponse>;
  /** The current status of every step `options.features` needs, without prompting. */
  getScreenTimePermissionsAsync(
    options?: ScreenTimePermissionOptions,
  ): Promise<ScreenTimePermissionResponse>;

  // Capabilities
  /**
   * What this build, on this device, with the authorization last granted, can
   * do. Synchronous and cheap: call it at render time.
   */
  getCapabilities(): ScreenTimeCapabilities;
  /**
   * Periods when a block should have applied but could not, oldest first;
   * those still in progress at or ending after `since` (Unix ms) when given.
   * iOS reports Screen Time authorization revocations, observed at launch and
   * whenever the app returns to the foreground: a gap opens at the last time
   * access was seen granted and closes when it is seen granted again.
   */
  getEnforcementGaps(since?: number): Promise<EnforcementGap[]>;
  /**
   * Bring an app to the foreground by handle. iOS cannot launch another app:
   * rejects `UNSUPPORTED` unless `getCapabilities().openApplication`.
   */
  openApplication(handle: string): Promise<void>;

  // App Control
  denyAppRemoval(): void;
  allowAppRemoval(): void;
  denyAppInstallation(): void;
  allowAppInstallation(): void;

  // Blocking
  /**
   * Shield a stored selection — the manual block, one source among the
   * schedules and thresholds in progress; it replaces the previous manual
   * block. `mode: "allowlist"` shields every app except the selection's
   * apps instead (applications only). Throws `SELECTION_NOT_FOUND`, or
   * `INVALID_CONFIG` for an allowlist holding categories or web domains,
   * or nothing at all.
   */
  blockSelection(selectionId: string, options?: { mode?: BlockMode }): void;
  unblockAll(): void;
  /** Whether anything is currently shielded (reflects the real ManagedSettings state). */
  isBlocked(): boolean;
  blockWebDomains(domains: string[]): void;
  unblockWebDomains(domains: string[]): void;
  unblockAllWebDomains(): void;
  getBlockedWebDomains(): string[];

  // Temporary Unlock (earned time)
  /**
   * Suspend the current block for a usage budget of `budgetSeconds`.
   *
   * The budget is **usage-based, not wall-clock**: it is only spent while a
   * blocked app is actually in the foreground — it pauses when the user
   * leaves and resumes when they come back. The shield re-applies
   * automatically (from the DeviceActivityMonitor extension) once the budget
   * is spent, and any unspent budget expires at midnight.
   *
   * Budgets below 30s are raised to 30s (iOS usage thresholds are unreliable
   * under that); the grant's `meteringSeconds` says how coarsely it is
   * metered. Rejects with `NO_ACTIVE_BLOCK` when nothing is blocked, and
   * with `UNSUPPORTED` while an allowlist block is active: it shields every
   * other app, whose use no meter can count. A new call replaces any active
   * unlock; `blockSelection` cancels it.
   *
   * With `options.target` — a `TokenHandle` from `getTokens` or a shield
   * interaction — only that app, category or web domain is lifted, for
   * `budgetSeconds` of **its** use, and everything else stays shielded: the
   * shield `snooze` action, granted from the app. It is independent of the
   * whole-block unlock (`isTemporarilyUnlocked` and
   * `getRemainingUnlockSeconds` do not report it); a second targeted grant
   * or a snooze on the same target replaces the first; `relock`,
   * `blockSelection` and `unblockAll` end it. The target must be shielded
   * by an active block — an app an allowlist shields qualifies — else
   * `NO_ACTIVE_BLOCK`; tokens are opaque, so an app shielded only through
   * its category is not known to be shielded: target the category (the
   * handle a shield interaction reports for such an app). `UNSUPPORTED`
   * for a category while an allowlist is active; `INVALID_CONFIG` for a
   * handle that does not decode to a token (pass handles unchanged: a
   * handle from another device or of another type is misuse the module
   * cannot detect); `EXCESSIVE_ACTIVITIES` when no DeviceActivity slot is
   * free for the meter.
   * @platform ios
   */
  temporaryUnlock(
    budgetSeconds: number,
    options?: { target?: TokenHandle },
  ): Promise<TemporaryUnlockGrant>;
  /**
   * Whether a whole-block earned-time budget is currently active (targeted
   * grants and snoozes are not reported). @platform ios
   */
  isTemporarilyUnlocked(): boolean;
  /**
   * Seconds left on the active whole-block unlock budget, or 0.
   *
   * Moves in `meteringSeconds` decrements (30s for budgets up to 30min) as
   * the monitor extension reports consumption — don't render it as a smooth
   * countdown. Reading 0 after a grant also re-applies the shield as a
   * backstop.
   * @platform ios
   */
  getRemainingUnlockSeconds(): number;
  /**
   * End any active unlock — the whole-block one, every targeted grant and
   * every shield snooze — now and restore the shield. @platform ios
   */
  relock(): void;

  /**
   * Read-and-clear the queue of shield interactions recorded natively — one
   * entry each time the user reacted to the shield over a blocked app
   * (interactions within 2s collapse into one attempt). Call on app
   * foreground and persist app-side to power "blocked N times" stats.
   * @platform ios
   */
  drainShieldInteractions(): ShieldInteraction[];

  // Selection Store
  listSelections(): Promise<string[]>;
  hasSelection(selectionId: string): Promise<boolean>;
  getSelectionMetadata(selectionId: string): Promise<SelectionMetadata | null>;
  deleteSelection(selectionId: string): Promise<void>;
  getTokens(selectionId: string): Promise<TokenHandle[] | null>;
  removeToken(selectionId: string, handle: string): Promise<void>;

  // Selection Set Operations
  mergeSelections(targetId: string, sourceIds: string[]): Promise<void>;
  keepCommonSelections(targetId: string, sourceIds: string[]): Promise<void>;
  subtractSelection(
    targetId: string,
    fromId: string,
    subtractId: string,
  ): Promise<void>;

  // Selection Export/Import
  exportSelection(selectionId: string): Promise<string | null>;
  importSelection(selectionId: string, data: string): Promise<boolean>;

  // Reporting
  /**
   * Write a serialized report template (JSON string) to the native template
   * slot (the App Group on iOS, the module's own slot on Android) for the
   * report renderer. Prefer the `<DeviceActivityReport>` children API or
   * `setReportTemplate(element)` over calling this directly. Throws
   * `UNSUPPORTED` where `getCapabilities().usageReports` is off.
   */
  setReportTemplate(templateJson: string): void;
  /** Remove the custom template — the report falls back to its placeholder. */
  clearReportTemplate(): void;
  getAppGroup(): string | null;

  // Monitoring
  startMonitoring(config: MonitoringConfiguration): Promise<void>;
  stopMonitoring(activityName: string): Promise<void>;
  stopAllMonitoring(): Promise<void>;
  getMonitoredActivities(): Promise<string[]>;

  // Shield Configuration
  setShieldConfig(config: ShieldConfig): void;
  setShieldConfigForActivity(activityName: string, config: ShieldConfig): void;
  clearShieldConfig(): void;

  // Shield Actions
  setShieldActions(config: ShieldActionConfig): void;
  setShieldActionsForActivity(
    activityName: string,
    config: ShieldActionConfig,
  ): void;
  clearShieldActions(): void;

  // Event History
  getMonitorEvents(
    activityName?: string,
    limit?: number,
  ): Promise<MonitorEvent[]>;
  clearMonitorEvents(activityName?: string): Promise<void>;

  // Debug
  /**
   * Debug-only: dumps the App Group, active shield config, and the shield
   * extension's log tail. Not part of the stable API, may change or vanish.
   */
  debugShieldConfig(): Promise<Record<string, unknown>>;
}

export default requireNativeModule<ExpoScreenTimeModule>("ExpoScreenTime");
