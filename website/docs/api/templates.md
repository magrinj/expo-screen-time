---
id: templates
title: Report Templates
sidebar_position: 8
---

# Report Templates

:::info Both platforms
On iOS the report needs the `DeviceActivityReportExtension` target; on
Android it needs the `usageReports` feature (on by default) and the
`usageAccess` permission step. `getCapabilities().usageReports` says whether
this build renders reports.
:::

Compose the report screen's **entire layout in JSX** with `Report.*` components.
The tree is serialized (never rendered by React) and rendered **natively**
against the device's usage data — inside Apple's sandboxed report extension on
iOS, by the module's own renderer from UsageEvents on Android. The layout comes
from JS; the values never reach JavaScript on either platform.

```tsx
import { DeviceActivityReport, Report } from 'expo-screen-time';

<DeviceActivityReport filterSegment="daily" style={{ height: 420 }}>
  <Report.VStack spacing={14} padding={16} alignment="leading">
    <Report.HStack spacing={14}>
      <Report.Gauge bind="totalDuration" goal={4 * 3600} size={64} color="#AF52DE" />
      <Report.VStack spacing={2} alignment="leading">
        <Report.Text text="SCREEN TIME" size={11} color="#8E8E93" letterSpacing={1} />
        <Report.Text bind="totalDuration" durationStyle="abbreviated" size={30} weight="bold" monospacedDigit />
        <Report.Text text="of 4h daily goal" size={12} color="#8E8E93" />
      </Report.VStack>
    </Report.HStack>
    <Report.ProgressBar bind="totalDuration" goal={4 * 3600} height={10} color="#AF52DE" />
    <Report.Chart type="area" bind="hourly" accentColor="#AF52DE" gradient height={90} showYAxis={false} />
    <Report.Divider />
    <Report.TopApps limit={3} spacing={10} emptyText="No app usage yet">
      <Report.HStack spacing={10}>
        <Report.AppIcon size={26} cornerRadius={6} />
        <Report.AppTitle size={14} />
        <Report.Spacer />
        <Report.Text bind="appDuration" durationStyle="short" size={13} monospacedDigit />
      </Report.HStack>
    </Report.TopApps>
  </Report.VStack>
</DeviceActivityReport>
```

The report is template-only: without a template (children or the imperative
[`setReportTemplate`](#imperative-api)), it renders a "No custom template set"
placeholder.

## Rules

- **Templates are descriptors, not React UI.** Only `Report.*` components and
  hook-free compositions of them are allowed — React Native components can't
  render inside the extension, and hooks can't run during serialization. Both
  produce descriptive errors in development.
- **One template at a time.** The native slot carries a single template — the
  last mounted templated report wins.
- **Size the container.** The report can't report its content height back to
  React Native: the template is clipped to the component's `style`. Give it a
  `height` that fits your layout.
- **Display-only.** No presses/callbacks from the report back to the app.
  Scrolling *over* the report is handled for you.
- **First render can be slow on iOS.** The very first scene invocation after a
  fresh install (or freshly enabled Screen Time) can take up to a minute while
  iOS spins up its report service; subsequent renders are fast. Android reads
  the usage events in a few hundred milliseconds.
- **Not live.** The data is read when the report mounts or its props change;
  remount it (a `key`) to refresh.

## Bindings

`bind` props resolve to values computed natively from the report's usage data
— JS only names them, it never sees them:

| Binding | Scope | Value |
|---|---|---|
| `totalDuration` | root | Total screen time (seconds) |
| `pickups` | root | Number of device pickups: every time the screen woke up, lock-screen glances included |
| `longestActivity` | root | Longest continuous session (seconds) |
| `appCount` | root | Number of distinct apps used |
| `firstPickup` | root | Time of the first pickup ("9:41 AM", or "—") |
| `appDuration` | item | The current app/category's usage (seconds) |
| `appName` | item | The current app/category's display name |
| `rank` | item | 1-based position in the list |

**Pickups.** A pickup is a screen wake, as Apple's Screen Time counts them: on
Android every `SCREEN_INTERACTIVE` event in the window is one, whether or not
the device was unlocked afterwards, and `firstPickup` is the first wake.

**Categories on Android** come from the category each developer declares in
its manifest (`ApplicationInfo.category`: Game, Audio, Video, Image, Social,
News, Maps, Productivity, Accessibility, with the system's localized title).
Most apps declare none and land in "Other", so Android categories are coarse
next to Apple's taxonomy.

**Scopes.** Root bindings work anywhere. Item bindings only work inside the
per-item template of [`TopApps` / `Categories`](#reporttopapps--reportcategories)
(a descriptive error is thrown otherwise).

**Formatting durations.** On `Report.Text`, `durationStyle` picks the format,
the unit styles of Foundation's `DateComponentsFormatter` on iOS and of ICU's
`MeasureFormat` on Android (the same data) in the device locale (en-US shown):
`'abbreviated'` (1h 24m), `'short'` (1 hr, 24 min), `'full'` (1 hour,
24 minutes). Under an hour only minutes show (24m, 24 min, 24 minutes).
Alternatively `format="percent"` + `goal` renders any numeric binding as a
percentage.

**Weekly filter.** With `filterSegment="weekly"`, `hourly` chart buckets
aggregate the week per hour-of-day (a daily usage profile, not a timeline),
and duration bindings cover the whole week.

## Layout components

### `Report.VStack` / `Report.HStack`

Vertical / horizontal stack. Accepts all [box props](#box-props).

| Prop | Type | Default | Description |
|---|---|---|---|
| `spacing` | `number` | SwiftUI default (~8) | Points between children |
| `alignment` | `'leading' \| 'center' \| 'trailing' \| 'top' \| 'bottom'` | `'center'` | Cross-axis alignment: use leading/trailing in a VStack, top/bottom in an HStack |
| `children` | `Report.*` | — | Stack content |

### `Report.ZStack`

Overlays its children. Accepts all [box props](#box-props).

| Prop | Type | Default | Description |
|---|---|---|---|
| `alignment` | `'center' \| 'topLeading' \| 'topTrailing' \| 'bottomLeading' \| 'bottomTrailing'` | `'center'` | Where children anchor |

### `Report.Grid`

Fixed-column grid (`LazyVGrid`) — great for stat tiles. Accepts all
[box props](#box-props).

| Prop | Type | Default | Description |
|---|---|---|---|
| `columns` | `number` | `2` | Equal-width column count |
| `spacing` | `number` | `8` | Points between cells (both axes) |

### `Report.Spacer`

Flexible space — pushes siblings apart inside a stack.

| Prop | Type | Default | Description |
|---|---|---|---|
| `minLength` | `number` | `0` | Minimum size in points |

### `Report.Divider`

Horizontal hairline.

| Prop | Type | Default | Description |
|---|---|---|---|
| `color` | `string` (hex) | system separator | Line color |
| `thickness` | `number` | hairline (1px) | Line height in points |

### Box props

Shared by `VStack`, `HStack`, `ZStack` and `Grid`:

| Prop | Type | Default | Description |
|---|---|---|---|
| `padding` | `number` | `0` | Uniform padding (points) |
| `paddingHorizontal` / `paddingVertical` | `number` | `0` | Axis padding, additive with `padding` |
| `backgroundColor` | `string` (hex) | transparent | Fill color |
| `backgroundGradient` | `[string, string]` | — | Two-color gradient fill; overrides `backgroundColor` |
| `gradientDirection` | `'vertical' \| 'horizontal' \| 'diagonal'` | `'vertical'` | Gradient axis |
| `cornerRadius` | `number` | `0` | Rounds background and border |
| `borderColor` / `borderWidth` | `string` / `number` | — / `0` | Stroked border following `cornerRadius` |
| `shadowColor` | `string` (hex, supports `#RRGGBBAA`) | none | Drop shadow color |
| `shadowRadius` / `shadowY` | `number` | `0` / `0` | Shadow blur / vertical offset |
| `opacity` | `number` | `1` | 0–1 |
| `width` / `height` / `minHeight` | `number` | content size | Fixed frame in points |
| `fillWidth` | `boolean` | `false` | Stretch to the available width |

Colors are hex strings: `#RRGGBB` or `#RRGGBBAA`.

## Content components

### `Report.Text`

A text element — static (`text`) **or** bound to a native value (`bind`).
Exactly one of the two is required.

| Prop | Type | Default | Description |
|---|---|---|---|
| `text` | `string` | — | Static content (string children work too) |
| `bind` | binding key | — | Native value to display (see [Bindings](#bindings)) |
| `durationStyle` | `'abbreviated' \| 'short' \| 'full'` | `'abbreviated'` | Format for duration bindings |
| `format` | `'percent'` | — | Render the binding as `Math.round(value / goal * 100)%` |
| `goal` | `number` | — | Required with `format="percent"`, in the binding's unit |
| `size` | `number` | `15` | Font size (points) |
| `weight` | `'light' \| 'regular' \| 'medium' \| 'semibold' \| 'bold' \| 'heavy'` | `'regular'` | Font weight |
| `design` | `'default' \| 'rounded' \| 'serif' \| 'monospaced'` | `'default'` | Font design (`rounded` = SF Rounded) |
| `color` | `string` (hex) | label color | Text color |
| `opacity` | `number` | `1` | 0–1 |
| `italic` / `underline` / `strikethrough` | `boolean` | `false` | Decorations |
| `uppercase` | `boolean` | `false` | Uppercases the resolved text |
| `monospacedDigit` | `boolean` | `false` | Tabular figures — keeps bound numbers from jiggling |
| `lineLimit` | `number` | `1` | Max lines |
| `letterSpacing` | `number` | `0` | Kerning in points |
| `align` | `'leading' \| 'center' \| 'trailing'` | — | Multiline alignment; also stretches to available width |

### `Report.Symbol`

An SF Symbol. On Android a bundled Material Symbol stands in for a documented
set of names: `hourglass`, `hourglass.bottomhalf.filled`, `clock`, `clock.fill`,
`timer`, `calendar`, `chart.bar`, `chart.bar.fill`, `chart.pie`,
`chart.pie.fill`, `apps.iphone`, `square.grid.2x2`, `iphone`, `hand.raised`,
`hand.raised.fill`, `lock`, `lock.fill`, `nosign`, `moon.fill`, `moon.zzz.fill`,
`exclamationmark.triangle.fill`, `checkmark.circle`, `checkmark.circle.fill`,
`star`, `star.fill`, `bell`, `bell.fill`, `eye`, `eye.fill`, `arrow.up`,
`arrow.down`, `gamecontroller`, `gamecontroller.fill`, `questionmark`. Any
other name leaves an empty slot of `size` on Android, so the layout around it
holds. `renderingMode` and `weight` are iOS-only (Android tints the glyph).

| Prop | Type | Default | Description |
|---|---|---|---|
| `name` | `string` | required | SF Symbol name (e.g. `"hourglass"`) |
| `size` | `number` | `17` | Point size |
| `color` | `string` (hex) | label color | Tint |
| `weight` | font weight | `'regular'` | Symbol weight |
| `renderingMode` | `'monochrome' \| 'hierarchical'` | `'monochrome'` | `hierarchical` renders multi-level symbols with depth |
| `opacity` | `number` | `1` | 0–1 |

### `Report.Chart`

A Swift Charts plot of usage data.

| Prop | Type | Default | Description |
|---|---|---|---|
| `type` | `'bar' \| 'line' \| 'area' \| 'pie'` | required | Mark type. `pie` needs iOS 17 (falls back to `bar`); every type renders on Android |
| `bind` | `'hourly' \| 'topApps' \| 'categories'` | required | Data series: 24 hour-of-day buckets, or per-app/per-category totals |
| `accentColor` | `string` (hex) | blue | Series color |
| `colorPalette` | `string[]` | — | Per-entry colors (bar/pie with list binds); cycles when shorter |
| `gradient` | `boolean` | `false` | Vertical fade of the accent color (bar/area) |
| `barCornerRadius` | `number` | `0` | Rounded bar marks |
| `lineWidth` | `number` | `2` | Line chart stroke width |
| `showPoints` | `boolean` | `false` | Line charts: a point at each value |
| `averageLine` | `boolean` | `false` | Dashed rule at the series average |
| `goalValue` | `number` | — | Solid-dash goal rule, **in minutes** (chart Y unit) |
| `goalColor` | `string` (hex) | red | Goal rule color |
| `pieInnerRadius` | `number` | `0.55` | Donut hole ratio 0–1 (`0` = solid pie) |
| `labelColor` | `string` (hex) | secondary label | Axis label color |
| `height` | `number` | `120` | Chart height (points) |
| `showXAxis` | `boolean` | `false` for `hourly`, else `true` | X-axis labels/grid |
| `showYAxis` | `boolean` | `true` | Y-axis labels/grid |
| `xAxisStride` | `number` | `6` | Hourly X-axis: label every N hours |
| `maxItems` | `number` | `6` | Max entries for `topApps`/`categories` binds |

### `Report.ProgressBar`

Linear progress toward a goal: `bind / goal`, clamped to 0–1.

| Prop | Type | Default | Description |
|---|---|---|---|
| `bind` | binding key | `totalDuration` | Numeric binding |
| `goal` | `number` | required | In the binding's unit (seconds for durations, count for pickups) |
| `height` | `number` | `8` | Bar height (points) |
| `color` | `string` (hex) | blue | Fill |
| `trackColor` | `string` (hex) | quaternary fill | Track |
| `cornerRadius` | `number` | `height / 2` | Capsule by default |

### `Report.Gauge`

Progress toward a goal as a SwiftUI Gauge.

| Prop | Type | Default | Description |
|---|---|---|---|
| `bind` | binding key | `totalDuration` | Numeric binding |
| `goal` | `number` | required | In the binding's unit |
| `style` | `'circular' \| 'linear'` | `'circular'` | Circular ring with a % label, or a slim capacity bar |
| `size` | `number` | `56` | Circular diameter in points — any size works, the ring is scaled internally |
| `color` | `string` (hex) | blue | Tint |

## Item lists

### `Report.TopApps` / `Report.Categories`

Repeats their children once per app / category (sorted by usage, descending).
Inside, the **item scope** enables `appDuration` / `appName` / `rank` bindings
and the `AppIcon` / `AppTitle` components.

| Prop | Type | Default | Description |
|---|---|---|---|
| `limit` | `number` | `5` | Max rows |
| `spacing` | `number` | `8` | Points between rows |
| `emptyText` | `string` | — | Shown when there is no usage data yet |
| `children` | `Report.*` | required | The per-item template |

### `Report.AppIcon`

The current item's real app/category icon: on iOS rendered from the opaque
token via `Label(token)` (only possible inside the sandbox), on Android the
app's launcher icon from the package manager. Android categories have no icon
and show a category glyph on the placeholder fill. Item scope only.

| Prop | Type | Default | Description |
|---|---|---|---|
| `size` | `number` | `28` | Icon size (points) |
| `cornerRadius` | `number` | `0` | Clip radius |

### `Report.AppTitle`

The current item's display name. Item scope only.

| Prop | Type | Default | Description |
|---|---|---|---|
| `size` | `number` | `15` | Font size |
| `weight` | font weight | `'regular'` | Font weight |
| `color` | `string` (hex) | label color | Text color |
| `opacity` | `number` | `1` | 0–1 |
| `lineLimit` | `number` | `1` | Max lines |

```tsx
<Report.Categories limit={4} emptyText="Nothing yet">
  <Report.HStack spacing={8}>
    <Report.AppIcon size={22} />
    <Report.AppTitle size={13} />
    <Report.Spacer />
    <Report.Text bind="appDuration" durationStyle="short" size={12} />
  </Report.HStack>
</Report.Categories>
```

## Imperative API

The children API above is the reactive path and should be preferred. For cases
where the template is set outside a render (e.g. app startup config):

```ts
import { setReportTemplate, clearReportTemplate, Report } from 'expo-screen-time';

setReportTemplate(
  <Report.VStack padding={16}>
    <Report.Text bind="totalDuration" durationStyle="abbreviated" size={32} weight="bold" />
  </Report.VStack>
);
// Any mounted <DeviceActivityReport> without children renders it.

clearReportTemplate(); // back to the placeholder
```

## Platform notes

The same template JSON is rendered by SwiftUI on iOS and by Android views on
Android, with the same bindings, units, defaults and copy. Where the platforms
differ:

- **Fonts.** `design="rounded"` has no Android face and renders as the default
  sans-serif; `serif` and `monospaced` are the system ones.
- **Shadows.** `shadowY` has no Android counterpart; the elevation's own offset
  stands in.
- **Symbols.** See [`Report.Symbol`](#reportsymbol) for the names Android
  renders.
- **Categories and pickups.** See [Bindings](#bindings).
- **Without usage access** (the `usageAccess` permission step) the Android
  report renders zeros, as iOS does without Screen Time authorization.

## Versioning

The serialized template carries a `version`; a native side older than the JS
falls back to the placeholder (and unknown components/props are skipped) —
templates degrade, they don't crash.
