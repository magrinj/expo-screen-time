import { createPermissionHook } from "expo-modules-core";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactElement,
} from "react";

import type {
  MonitorEvent,
  OnMonitorEventPayload,
} from "./ExpoScreenTime.types";
import ExpoScreenTimeModule from "./ExpoScreenTimeModule";
import { serializeTemplate } from "./report/serializeTemplate";
import {
  acquireTemplate,
  clearTemplateChannel,
  IMPERATIVE_OWNER,
} from "./report/templateChannel";

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
    const subscription = ExpoScreenTimeModule.addListener(
      "onMonitorEvent",
      (payload: OnMonitorEventPayload) => {
        const event = payload.event;
        setEvents((prev) => [event, ...prev].slice(0, 100));
        callbackRef.current?.(event);
      },
    );
    setIsListening(true);

    return () => {
      subscription.remove();
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

// Report templates
export { Report } from "./report/ReportComponents";
export type {
  ReportBinding,
  ReportItemBinding,
  DurationStyle,
} from "./report/ReportComponents";
export { serializeTemplate };
export type {
  ReportTemplate,
  ReportTemplateNode,
} from "./report/serializeTemplate";

/**
 * Imperative form of the `<DeviceActivityReport>` children API: serialize a
 * `Report.*` element tree and ship it to the report extension via the App
 * Group. Any mounted `<DeviceActivityReport>` without children renders it —
 * the children API is the reactive path and should be preferred.
 * @platform ios
 */
export function setReportTemplate(element: ReactElement): void {
  acquireTemplate(IMPERATIVE_OWNER, JSON.stringify(serializeTemplate(element)));
}

/** Remove the custom report template (revert to built-in layouts). @platform ios */
export function clearReportTemplate(): void {
  clearTemplateChannel();
}

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

// Errors
export { isScreenTimeError, SCREEN_TIME_ERROR_CODES } from "./errors";
export type { ScreenTimeError, ScreenTimeErrorCode } from "./errors";

// Types
export * from "./ExpoScreenTime.types";
