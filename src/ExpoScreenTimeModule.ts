import { NativeModule, requireNativeModule } from "expo";
import type { PermissionResponse } from "expo-modules-core";

import type {
  ExpoScreenTimeModuleEvents,
  MonitoringConfiguration,
  ShieldConfig,
  ShieldActionConfig,
  SelectionMetadata,
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
  setReportStyle(style: ReportStyle): void;
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

export interface ReportStyle {
  accentColor?: string;
  backgroundColor?: string;
  textColor?: string;
  showPickups?: boolean;
  showTopApps?: boolean;
  showChart?: boolean;
  chartStyle?: "bar" | "pie";
}

export default requireNativeModule<ExpoScreenTimeModule>("ExpoScreenTime");
