import type { ReactNode } from "react";

// ============================================================================
// REPORT TEMPLATE COMPONENTS (markers)
//
// These components are descriptors, never rendered by React: the serializer
// reads their type/props and ships a JSON tree to the DeviceActivityReport
// extension through the App Group. Values are resolved INSIDE Apple's sandbox
// — JS never sees usage data (see docs/architecture.md
// template-v1-design.md).
//
// Naming/prop conventions mirror SwiftUI/@expo/ui (same vocabulary as
// expo-widgets layouts).
// ============================================================================

/** Root-scope binding keys, resolved from DeviceActivityResults natively. */
export type ReportBinding =
  | "totalDuration"
  | "pickups"
  | "longestActivity"
  | "appCount"
  | "firstPickup";

/** Item-scope binding keys (inside `Report.TopApps` / `Report.Categories`). */
export type ReportItemBinding = "appDuration" | "appName" | "rank";

/** Duration formatting presets (DateComponentsFormatter styles). */
export type DurationStyle = "abbreviated" | "short" | "full";

type FontWeight =
  | "regular"
  | "medium"
  | "semibold"
  | "bold"
  | "heavy"
  | "light";

/** Frame/decoration props shared by the stack containers. */
type BoxProps = {
  padding?: number;
  paddingHorizontal?: number;
  paddingVertical?: number;
  backgroundColor?: string;
  /** Two-color background gradient — overrides `backgroundColor`. */
  backgroundGradient?: [string, string];
  gradientDirection?: "vertical" | "horizontal" | "diagonal";
  cornerRadius?: number;
  borderColor?: string;
  borderWidth?: number;
  opacity?: number;
  shadowColor?: string;
  shadowRadius?: number;
  /** Vertical shadow offset, in points. */
  shadowY?: number;
  /** Fixed frame, in points. Omit to size to content. */
  width?: number;
  height?: number;
  minHeight?: number;
  /** Stretch to the available width. */
  fillWidth?: boolean;
};

export type ReportStackProps = BoxProps & {
  /** Points between children. */
  spacing?: number;
  /** Cross-axis alignment: `leading`/`trailing` apply to a VStack,
   * `top`/`bottom` to an HStack. Default `center`. */
  alignment?: "leading" | "center" | "trailing" | "top" | "bottom";
  children?: ReactNode;
};

export type ReportZStackProps = BoxProps & {
  alignment?:
    | "center"
    | "topLeading"
    | "topTrailing"
    | "bottomLeading"
    | "bottomTrailing";
  children?: ReactNode;
};

export type ReportTextProps = {
  /** Static text. Mutually exclusive with `bind`. */
  text?: string;
  /** Bind to a native value (root scope, or item bindings in an item scope). */
  bind?: ReportBinding | ReportItemBinding;
  /** How to format a duration binding. */
  durationStyle?: DurationStyle;
  /** `percent` renders the binding as a percentage of `goal` (e.g. "42%"). */
  format?: "percent";
  /** Goal for `format="percent"`, in the binding's unit (seconds/count). */
  goal?: number;
  size?: number;
  weight?: FontWeight;
  /** Font design — `rounded` is SF Rounded. */
  design?: "default" | "rounded" | "serif" | "monospaced";
  color?: string;
  opacity?: number;
  italic?: boolean;
  underline?: boolean;
  strikethrough?: boolean;
  uppercase?: boolean;
  /** Tabular figures — keeps bound numbers from jiggling. */
  monospacedDigit?: boolean;
  lineLimit?: number;
  letterSpacing?: number;
  /** Multiline alignment; also stretches the text to the available width. */
  align?: "leading" | "center" | "trailing";
  /** String children are sugar for `text`. */
  children?: string;
};

export type ReportSymbolProps = {
  /** SF Symbol name. */
  name: string;
  size?: number;
  color?: string;
  weight?: FontWeight;
  opacity?: number;
  /** `hierarchical` renders multi-level symbols with depth. */
  renderingMode?: "monochrome" | "hierarchical";
};

export type ReportChartProps = {
  /** `pie` requires iOS 17 (renders as `bar` on iOS 16). */
  type: "bar" | "pie" | "line" | "area";
  /** `hourly` = 24 hour-of-day buckets; with a `weekly` filter the days are
   * aggregated per hour-of-day (a daily usage profile, not a timeline). */
  bind: "hourly" | "topApps" | "categories";
  accentColor?: string;
  /** Per-entry colors (topApps/categories/pie) — cycles when shorter. */
  colorPalette?: string[];
  /** Vertical linear gradient fade of the accent color (bar/area). */
  gradient?: boolean;
  /** Corner radius of bar marks. */
  barCornerRadius?: number;
  /** Line/point charts: stroke width. */
  lineWidth?: number;
  /** Line charts: draw a point at each value. */
  showPoints?: boolean;
  /** Dashed horizontal rule at the average value. */
  averageLine?: boolean;
  /** Horizontal goal rule, in minutes. */
  goalValue?: number;
  goalColor?: string;
  /** Pie: inner radius ratio 0–1 (donut hole, default 0.55). */
  pieInnerRadius?: number;
  labelColor?: string;
  height?: number;
  showXAxis?: boolean;
  showYAxis?: boolean;
  /** Hourly X-axis: label every N hours (default 6). */
  xAxisStride?: number;
  /** Max entries for topApps/categories binds (default 6). */
  maxItems?: number;
};

export type ReportListProps = {
  /** Max items (default 5, applied natively). */
  limit?: number;
  spacing?: number;
  /** Shown when there is no usage data yet. */
  emptyText?: string;
  /** Per-item template — rendered once per app/category in the sandbox. */
  children?: ReactNode;
};

export type ReportAppIconProps = {
  /** Icon size in points (default 28). */
  size?: number;
  cornerRadius?: number;
};

export type ReportAppTitleProps = {
  size?: number;
  weight?: FontWeight;
  color?: string;
  opacity?: number;
  lineLimit?: number;
};

export type ReportSpacerProps = { minLength?: number };

export type ReportDividerProps = {
  color?: string;
  thickness?: number;
};

/**
 * Progress toward a goal. `bind` (a duration/count binding) is divided by
 * `goal` (same unit: seconds for durations, count for pickups).
 */
export type ReportProgressBarProps = {
  bind?: ReportBinding | ReportItemBinding;
  /** Goal in the binding's unit (seconds for durations). */
  goal: number;
  height?: number;
  color?: string;
  trackColor?: string;
  cornerRadius?: number;
};

/** Progress toward a goal (SwiftUI Gauge). */
export type ReportGaugeProps = {
  bind?: ReportBinding | ReportItemBinding;
  /** Goal in the binding's unit (seconds for durations). */
  goal: number;
  /** `circular` (default) shows a % in the center; `linear` is a slim bar. */
  style?: "circular" | "linear";
  /** Circular diameter in points (default 56). Any size works — the ring is
   * rendered at its natural size and scaled, never clipped. */
  size?: number;
  color?: string;
};

/** Fixed-column grid (LazyVGrid) — great for stat tiles. */
export type ReportGridProps = BoxProps & {
  /** Number of equal-width columns (default 2). */
  columns?: number;
  spacing?: number;
  children?: ReactNode;
};

/** Marker component: carries its native type name; throws if actually rendered. */
export type ReportComponent<P> = ((props: P) => null) & {
  $$reportType: string;
};

function createReportComponent<P>(type: string): ReportComponent<P> {
  const component = (() => {
    throw new Error(
      `[expo-screen-time] <Report.${type}> is a report-template descriptor and cannot be ` +
        `rendered by React. Use it inside <DeviceActivityReport> children (or pass it to ` +
        `setReportTemplate).`,
    );
  }) as unknown as ReportComponent<P>;
  component.$$reportType = type;
  // Helps React DevTools / error messages if it ever leaks into a tree.
  (component as any).displayName = `Report.${type}`;
  return component;
}

/**
 * Report-template vocabulary. Namespaced to avoid collisions (`Report.Text` vs
 * react-native's `Text`) and to signal "template land": these never render in
 * the app — they describe the report extension's UI.
 * @platform ios
 */
export const Report = {
  VStack: createReportComponent<ReportStackProps>("VStack"),
  HStack: createReportComponent<ReportStackProps>("HStack"),
  ZStack: createReportComponent<ReportZStackProps>("ZStack"),
  Grid: createReportComponent<ReportGridProps>("Grid"),
  Spacer: createReportComponent<ReportSpacerProps>("Spacer"),
  Divider: createReportComponent<ReportDividerProps>("Divider"),
  Text: createReportComponent<ReportTextProps>("Text"),
  Symbol: createReportComponent<ReportSymbolProps>("Symbol"),
  Chart: createReportComponent<ReportChartProps>("Chart"),
  ProgressBar: createReportComponent<ReportProgressBarProps>("ProgressBar"),
  Gauge: createReportComponent<ReportGaugeProps>("Gauge"),
  TopApps: createReportComponent<ReportListProps>("TopApps"),
  Categories: createReportComponent<ReportListProps>("Categories"),
  AppIcon: createReportComponent<ReportAppIconProps>("AppIcon"),
  AppTitle: createReportComponent<ReportAppTitleProps>("AppTitle"),
} as const;

/** Component types that introduce the per-item binding scope. */
export const ITEM_SCOPE_TYPES = new Set(["TopApps", "Categories"]);

/** Component types only valid inside an item scope. */
export const ITEM_ONLY_TYPES = new Set(["AppIcon", "AppTitle"]);

/** Binding keys only valid inside an item scope. */
export const ITEM_ONLY_BINDINGS = new Set(["appDuration", "appName", "rank"]);
