import { createPermissionHook } from "expo-modules-core";
import { useCallback, useEffect, useRef, useState } from "react";

import type {
  MonitorEvent,
  OnMonitorEventPayload,
} from "./ExpoScreenTime.types";
import ExpoScreenTimeModule from "./ExpoScreenTimeModule";

export const useScreenTimePermissions = createPermissionHook({
  requestMethod: ExpoScreenTimeModule.requestScreenTimePermissionsAsync,
  getMethod: ExpoScreenTimeModule.getScreenTimePermissionsAsync,
});

export function useMonitorEvents(callback?: (event: MonitorEvent) => void) {
  const [events, setEvents] = useState<MonitorEvent[]>([]);
  const [isListening, setIsListening] = useState(false);
  const callbackRef = useRef(callback);

  useEffect(() => {
    callbackRef.current = callback;
  }, [callback]);

  useEffect(() => {
    ExpoScreenTimeModule.startEventListening?.();
    setIsListening(true);

    const subscription = ExpoScreenTimeModule.addListener(
      "onMonitorEvent",
      (payload: OnMonitorEventPayload) => {
        const event = payload.event;
        setEvents((prev) => [event, ...prev].slice(0, 100));
        callbackRef.current?.(event);
      },
    );

    return () => {
      subscription.remove();
      ExpoScreenTimeModule.stopEventListening?.();
      setIsListening(false);
    };
  }, []);

  const clearEvents = useCallback(() => {
    setEvents([]);
  }, []);

  return { events, isListening, clearEvents };
}

// Module
export { default } from "./ExpoScreenTimeModule";
export type { ReportStyle } from "./ExpoScreenTimeModule";

// Components
export { default as ActivityPicker } from "./ExpoScreenTimeActivityPickerView";
export {
  default as TokenLabel,
  ApplicationLabel,
  CategoryLabel,
  WebDomainLabel,
} from "./ExpoScreenTimeTokenLabel";
export {
  default as DeviceActivityReport,
  DeviceActivityReport as ExpoScreenTimeReport,
} from "./DeviceActivityReport";
export type {
  DeviceActivityReportProps,
  ReportContext,
  FilterSegment,
} from "./DeviceActivityReport";

// Utilities
export {
  listSelections,
  hasSelection,
  getSelectionMetadata,
  deleteSelection,
  getTokens,
  removeToken,
  mergeSelections,
  keepCommonSelections,
  subtractSelection,
  exportSelection,
  importSelection,
} from "./utils";

// Types
export * from "./ExpoScreenTime.types";
