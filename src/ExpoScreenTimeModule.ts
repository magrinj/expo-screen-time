import { NativeModule, requireNativeModule } from "expo";
import type { PermissionResponse } from "expo-modules-core";

import type {
  ExpoScreenTimeModuleEvents,
  MonitoringConfiguration,
  ShieldConfig,
  ShieldActionConfig,
  SelectionMetadata,
  ShieldInteraction,
  TemporaryUnlockGrant,
  TokenHandle,
  MonitorEvent,
} from "./ExpoScreenTime.types";

declare class ExpoScreenTimeModule extends NativeModule<ExpoScreenTimeModuleEvents> {
  // Permissions
  requestScreenTimePermissionsAsync(): Promise<PermissionResponse>;
  getScreenTimePermissionsAsync(): Promise<PermissionResponse>;

  // App Control
  denyAppRemoval(): void;
  allowAppRemoval(): void;
  denyAppInstallation(): void;
  allowAppInstallation(): void;

  // Blocking
  blockSelection(selectionId: string): void;
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
   * under that). Rejects with `NO_ACTIVE_BLOCK` when nothing is blocked.
   * A new call replaces any active unlock; `blockSelection` cancels it.
   * @platform ios
   */
  temporaryUnlock(budgetSeconds: number): Promise<TemporaryUnlockGrant>;
  /** Whether an earned-time budget is currently active. @platform ios */
  isTemporarilyUnlocked(): boolean;
  /**
   * Seconds left on the active unlock budget, or 0.
   *
   * Moves in ~30s decrements as the monitor extension reports consumption —
   * don't render it as a smooth countdown. Reading 0 after a grant also
   * re-applies the shield as a backstop.
   * @platform ios
   */
  getRemainingUnlockSeconds(): number;
  /** End any active unlock now and restore the shield. @platform ios */
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
   * Write a serialized report template (JSON string) to the App Group for the
   * report extension to render. Prefer the `<DeviceActivityReport>` children
   * API or `setReportTemplate(element)` over calling this directly.
   */
  setReportTemplate(templateJson: string): void;
  /** Remove the custom template — the extension falls back to built-in layouts. */
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
