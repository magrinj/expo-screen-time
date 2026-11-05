---
id: components
title: Components
sidebar_position: 6
---

# Components

## `<ActivityPicker />` {#activity-picker}

Native view for selecting apps, categories, and web domains. The selection is
stored **natively** under the `selectionId` you provide — JS receives only counts
and opaque token handles. iOS embeds Apple's `FamilyActivityPicker`; Android
shows the library's own list of launchable apps ([Android notes](#android)).

```typescript
import { ActivityPicker, type OnSelectionChangeEvent } from 'expo-screen-time';

function AppPicker() {
  return (
    <ActivityPicker
      style={{ flex: 1 }}
      selectionId="my-blocklist"
      headerText="Select apps to block"
      footerText="Selected apps will be blocked"
      onSelectionChange={(event) => {
        const { metadata, tokens } = event.nativeEvent;
        console.log(`${metadata.applicationCount} apps, ${tokens.length} tokens`);
      }}
      onOpen={() => console.log('Picker opened')}
      onClose={() => console.log('Picker closed')}
    />
  );
}
```

**Props:**

| Prop | Type | Description |
|------|------|-------------|
| `selectionId` | `string` (required) | ID of the selection in the native store |
| `style` | `StyleProp<ViewStyle>` | Container style |
| `headerText` | `string` | Header text displayed in the picker |
| `footerText` | `string` | Footer text displayed in the picker |
| `onSelectionChange` | `(event: { nativeEvent: OnSelectionChangeEvent }) => void` | `nativeEvent` = `{ metadata: SelectionMetadata, tokens: TokenHandle[] }` |
| `onOpen` | `() => void` | Called when the picker view appears |
| `onClose` | `() => void` | Called when the picker view disappears |

After picking, use the same `selectionId` with
[`blockSelection`](./api/blocking.md) or
[`startMonitoring`](./api/monitoring.md), and manage it with the
[selection utilities](./api/selections.md).

## `<DeviceActivityReport />` {#device-activity-report}

:::info iOS needs the `DeviceActivityReportExtension` target; Android the `usageReports` feature
:::

Renders a JSX-authored template against the device's usage data, natively:
inside Apple's sandboxed report extension on iOS, from UsageEvents by the
module's own renderer on Android. **The layout comes from `Report.*`
children** — see [Report Templates](./api/templates.md) for the full component
vocabulary and bindings. The values never reach JavaScript. Renders a styled
fallback where `getCapabilities().usageReports` is `false` (a build without
the extension or the feature) and on web.

```tsx
import { DeviceActivityReport, Report } from 'expo-screen-time';

<DeviceActivityReport filterSegment="daily" style={{ height: 320 }}>
  <Report.VStack spacing={12} padding={16}>
    <Report.Text bind="totalDuration" durationStyle="abbreviated" size={34} weight="bold" />
    <Report.Chart type="area" bind="hourly" accentColor="#FF6B00" gradient height={100} />
  </Report.VStack>
</DeviceActivityReport>
```

**Props:**

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `children` | `Report.*` template | - | The report layout (see [Report Templates](./api/templates.md)); omit only when pairing with `setReportTemplate()` |
| `filterSegment` | `'daily' \| 'weekly'` | `'daily'` | Time filter |
| `filterDate` | `Date` | Today (at mount) | Date to show the report for |
| `style` | `StyleProp<ViewStyle>` | - | Container style — must be sized to fit the template (remote views can't auto-size) |

## Token Labels {#token-labels}

Render an app/category/domain name and icon from an opaque token handle. Due to
Apple's privacy design, token names can't be read programmatically — they can
only be rendered natively. Get handles from
[`getTokens(selectionId)`](./api/selections.md) or the picker's
`onSelectionChange`.

```typescript
import { getTokens, ApplicationLabel, CategoryLabel, WebDomainLabel } from 'expo-screen-time';

const tokens = await getTokens('my-blocklist');
const apps = tokens?.filter((t) => t.type === 'application') ?? [];

// Display an app's name and icon
<ApplicationLabel
  selectionId="my-blocklist"
  handle={apps[0].handle}
  displayMode="title-and-icon"
  style={{ height: 40 }}
/>
```

`CategoryLabel` and `WebDomainLabel` work the same way for `type: 'category'` and
`type: 'webDomain'` handles. The generic `TokenLabel` takes an explicit
`tokenType` prop instead:

```typescript
<TokenLabel
  selectionId="my-blocklist"
  handle={handle}
  tokenType="application"
  displayMode="icon-only"
/>
```

`selectionId` is optional. A handle is self-describing — the native side
resolves it on its own (on Android, through the library's handle map) — so a
label renders a handle wherever it came from, even one that is no longer part
of any stored selection (a token the user removed, a handle you persisted
earlier):

```typescript
// A handle you kept from an earlier getTokens() — no selection needed
<ApplicationLabel handle={savedHandle} displayMode="title-and-icon" />
```

With `selectionId`, the handle is looked up inside that selection instead and
renders a placeholder if it is not part of it — use it when the label should
follow the selection's contents. Either way the handle stays opaque to JS:
never parse or construct one. The label cannot tell an application handle from
a category handle, so pass each handle to the label of its own
`TokenHandle.type` (`ApplicationLabel` for `'application'`, and so on).

**Props (all labels):**

| Prop | Type | Description |
|------|------|-------------|
| `selectionId` | `string` (optional) | Selection to look the handle up in; omit to resolve the handle on its own |
| `handle` | `string` | Opaque token handle |
| `displayMode` | `'icon-only' \| 'title-only' \| 'title-and-icon'` | Default `'title-and-icon'` |
| `style` | `StyleProp<ViewStyle>` | Container style |
| `tokenType` | `'application' \| 'category' \| 'webDomain'` | `TokenLabel` only; must be the handle's `TokenHandle.type` |

## Android {#android}

**`<ActivityPicker />`** is drawn by the library (Android has no system
picker): a searchable, multi-select list of the apps the user can launch —
the ones with a launcher icon, which the library's `<queries>` declaration
makes visible without `QUERY_ALL_PACKAGES` — with their icons and names, in
the app's light or dark scheme. The apps the engine never blocks are not
listed either: the app embedding the picker (it cannot be blocked, as on
iOS), the launchers, the dialer, the emergency app, the keyboard, Settings
and the system UI, which stay reachable under every block (see
[blocking](./api/blocking.md#android)). A stored selection that already
holds one of them (picked with an older version, or imported from a device
where that package is an ordinary app) keeps it: the selection round-trip
is untouched, `getTokens` lists it and a label renders it, the engine
ignores it, and `removeToken` drops it; the picker simply has no row for
it. Apps only: there are no category or
web-domain rows while `getCapabilities().selectableKinds` reports them off.
The list pads itself for the system bars it is drawn under (the gesture or
button navigation bar, the status bar, a display cutout), so the footer and
the last rows stay visible in an edge-to-edge app.
Same props and events as iOS: `headerText` and `footerText` frame the list,
`onSelectionChange` fires on every tap with the counts and the handles,
`onOpen` / `onClose` when the view is attached and detached. Package names
never reach JS.

**Token labels** read the app's icon and name from the package manager.
`ApplicationLabel` renders a resolved handle; an app selected and uninstalled
since shows a generic icon with "Unavailable app"; a handle this install does
not know shows the same placeholder text as iOS. `CategoryLabel` and
`WebDomainLabel` always show their placeholder on Android (no such handles
exist there).

**`<DeviceActivityReport />`** renders the same `Report.*` template as iOS
from the device's UsageEvents, in the app process, with Android views: stacks,
text, charts, gauges, app icons from the package manager. Details and the
platform notes on the [templates page](./api/templates.md#platform-notes).
It needs the `usageAccess` permission step; without it the report shows
zeros.

**Light and dark.** The picker's background, the labels' text colours and
the report's default colours follow the activity's effective appearance —
what `useColorScheme()` reports — as the iOS views follow their trait
collection. An app that fixes
`userInterfaceStyle` in `app.json` needs
[`expo-system-ui`](https://docs.expo.dev/versions/latest/sdk/system-ui/)
installed for that setting to apply on Android; without it the activity
follows the system, and a label in a hard-coded light layout turns light
grey when the device is in dark mode.
