---
id: components
title: Components
sidebar_position: 5
---

# Components

## `<ActivityPicker />` {#activity-picker}

Native view for selecting apps, categories, and web domains. The selection is
stored **natively** under the `selectionId` you provide — JS receives only counts
and opaque token handles.

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

:::info Requires the `DeviceActivityReportExtension` target
:::

Renders a JSX-authored template against iOS Screen Time usage data, inside
Apple's sandboxed report extension. **The layout comes from `Report.*`
children** — see [Report Templates](./api/templates.md) for the full component
vocabulary and bindings. The values never reach JavaScript. Renders a styled
fallback on non-iOS platforms.

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

**Props (all labels):**

| Prop | Type | Description |
|------|------|-------------|
| `selectionId` | `string` | Selection containing the token |
| `handle` | `string` | Opaque token handle |
| `displayMode` | `'icon-only' \| 'title-only' \| 'title-and-icon'` | Default `'title-and-icon'` |
| `style` | `StyleProp<ViewStyle>` | Container style |
| `tokenType` | `'application' \| 'category' \| 'webDomain'` | `TokenLabel` only |
