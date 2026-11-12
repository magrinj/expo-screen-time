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
  /** Prevent removing apps from the device. */
  denyAppRemoval(): void;
  /** Allow removing apps again. */
  allowAppRemoval(): void;
  /** Prevent installing apps from the App Store. */
  denyAppInstallation(): void;
  /** Allow installing apps again. */
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
  /**
   * Lift every active block now (the manual block, every schedule and
   * threshold block in progress) and clear the web-domain filters; any
   * temporary unlock and shield snooze end. Monitoring keeps running.
   */
  unblockAll(): void;
  /** Whether anything is currently shielded (reflects the real ManagedSettings state). */
  isBlocked(): boolean;
  /**
   * Add domains to the persisted web-domain block.
   * @platform ios
   */
  blockWebDomains(domains: string[]): void;
  /**
   * Remove domains from the web-domain block.
   * @platform ios
   */
  unblockWebDomains(domains: string[]): void;
  /**
   * Clear the web-domain block.
   * @platform ios
   */
  unblockAllWebDomains(): void;
  /**
   * Domains blocked via `blockWebDomains`; persisted across relaunches.
   * @platform ios
   */
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
   */
  temporaryUnlock(
    budgetSeconds: number,
    options?: { target?: TokenHandle },
  ): Promise<TemporaryUnlockGrant>;
  /**
   * Whether a whole-block earned-time budget is currently active (targeted
   * grants and snoozes are not reported).
   */
  isTemporarilyUnlocked(): boolean;
  /**
   * Seconds left on the active whole-block unlock budget, or 0.
   *
   * Moves in `meteringSeconds` decrements (30s for budgets up to 30min) as
   * the monitor extension reports consumption — don't render it as a smooth
   * countdown. Reading 0 after a grant also re-applies the shield as a
   * backstop.
   */
  getRemainingUnlockSeconds(): number;
  /**
   * End any active unlock — the whole-block one, every targeted grant and
   * every shield snooze — now and restore the shield.
   */
  relock(): void;

  /**
   * Read-and-clear the queue of shield interactions recorded natively — one
   * entry each time the user reacted to the shield over a blocked app
   * (interactions within 2s collapse into one attempt). Call on app
   * foreground and persist app-side to power "blocked N times" stats.
   */
  drainShieldInteractions(): ShieldInteraction[];

  // Selection Store
  /** Every stored selection ID. */
  listSelections(): Promise<string[]>;
  /** Whether a selection is stored under that ID. */
  hasSelection(selectionId: string): Promise<boolean>;
  /** The selection's item counts (no token data), or `null` if absent. */
  getSelectionMetadata(selectionId: string): Promise<SelectionMetadata | null>;
  /** Delete the stored selection. */
  deleteSelection(selectionId: string): Promise<void>;
  /** Opaque handles for the selection's items, for token labels and `removeToken`. */
  getTokens(selectionId: string): Promise<TokenHandle[] | null>;
  /** Remove one item from a selection by its handle. */
  removeToken(selectionId: string, handle: string): Promise<void>;

  // Selection Set Operations
  /** Save the union of the sources under `targetId`. */
  mergeSelections(targetId: string, sourceIds: string[]): Promise<void>;
  /** Save the intersection of the sources under `targetId`. */
  keepCommonSelections(targetId: string, sourceIds: string[]): Promise<void>;
  /** Save `fromId` minus `subtractId` under `targetId`. */
  subtractSelection(
    targetId: string,
    fromId: string,
    subtractId: string,
  ): Promise<void>;

  // Selection Export/Import
  /**
   * The selection as an opaque string for backup. It imports back only on
   * the same device and account (the same install on Android).
   */
  exportSelection(selectionId: string): Promise<string | null>;
  /**
   * Store an exported selection under `selectionId`. Resolves `false`, leaving
   * the selection unchanged, when `data` is not a selection this install can
   * decode.
   */
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
  /**
   * The App Group the extensions share with the app (`group.<bundle id>`).
   * @platform ios
   */
  getAppGroup(): string | null;

  // Monitoring
  /**
   * Start, or update in place, a schedule that blocks `selectionId` during
   * its interval. Each activity is its own block source.
   */
  startMonitoring(config: MonitoringConfiguration): Promise<void>;
  /** Stop the activity and lift its shields; other sources are untouched. */
  stopMonitoring(activityName: string): Promise<void>;
  /**
   * Stop every activity started with `startMonitoring` and lift their
   * shields; the manual block, web-domain filters, a temporary unlock and
   * shield snoozes are untouched.
   */
  stopAllMonitoring(): Promise<void>;
  /** The activities you started (the module's own meters are not listed). */
  getMonitoredActivities(): Promise<string[]>;

  // Shield Configuration
  /** The default shield appearance. */
  setShieldConfig(config: ShieldConfig): void;
  /** The shield appearance while this monitoring activity shields an app. */
  setShieldConfigForActivity(activityName: string, config: ShieldConfig): void;
  /** Revert to the system default shield. */
  clearShieldConfig(): void;

  // Shield Actions
  /** What the shield buttons do by default. */
  setShieldActions(config: ShieldActionConfig): void;
  /** What the shield buttons do while this monitoring activity shields an app. */
  setShieldActionsForActivity(
    activityName: string,
    config: ShieldActionConfig,
  ): void;
  /** Revert the shield buttons to their default actions. */
  clearShieldActions(): void;

  // Event History
  /** Stored monitor events, all activities when `activityName` is omitted (default limit 100). */
  getMonitorEvents(
    activityName?: string,
    limit?: number,
  ): Promise<MonitorEvent[]>;
  /** Delete stored monitor events, of one activity or all. */
  clearMonitorEvents(activityName?: string): Promise<void>;

  // Debug
  /**
   * Debug-only: dumps the App Group, active shield config, and the shield
   * extension's log tail. Not part of the stable API, may change or vanish.
   * @platform ios
   */
  debugShieldConfig(): Promise<Record<string, unknown>>;
}

export default requireNativeModule<ExpoScreenTimeModule>("ExpoScreenTime");
