---
id: components
title: Components
sidebar_position: 5
---

# Components

## `<ExpoScreenTimeActivityPicker />` {#activity-picker}

Native view for selecting apps, categories, and web domains to block.

```typescript
import { ExpoScreenTimeActivityPicker, FamilyActivitySelection } from 'expo-screen-time';

function AppPicker() {
  const [selection, setSelection] = useState<FamilyActivitySelection | null>(null);

  return (
    <ExpoScreenTimeActivityPicker
      style={{ flex: 1 }}
      headerText="Select apps to block"
      footerText="Selected apps will be blocked"
      selection={selection}
      onSelectionChange={(event) => {
        setSelection(event.nativeEvent.selection);
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
| `style` | `StyleProp<ViewStyle>` | Container style |
| `headerText` | `string` | Header text displayed in the picker |
| `footerText` | `string` | Footer text displayed in the picker |
| `selection` | `FamilyActivitySelection \| null` | Current selection state |
| `onSelectionChange` | `(event) => void` | Called when selection changes |
| `onOpen` | `() => void` | Called when picker view appears |
| `onClose` | `() => void` | Called when picker view disappears |

## `<DeviceActivityReport />` {#device-activity-report}

:::info Requires the `DeviceActivityReportExtension` target
:::

Display usage analytics from iOS Screen Time.

```typescript
import { DeviceActivityReport } from 'expo-screen-time';

<DeviceActivityReport
  context="TotalActivity"
  filterSegment="daily"
  filterDate={new Date()}
  style={{ height: 300 }}
  accentColor="#FF6B00"
  backgroundColor="#1a1a1a"
  textColor="#ffffff"
  showPickups={true}
  showTopApps={true}
  showChart={true}
  chartStyle="bar"
/>
```

**Props:**

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `context` | `'TotalActivity' \| 'AppList' \| 'HourlyChart'` | `'TotalActivity'` | Report view type |
| `filterSegment` | `'daily' \| 'weekly'` | `'daily'` | Time filter |
| `filterDate` | `Date` | Today | Date to show report for |
| `style` | `StyleProp<ViewStyle>` | - | Container style |
| `accentColor` | `string` | - | Accent color (hex) |
| `backgroundColor` | `string` | - | Background color (hex) |
| `textColor` | `string` | - | Text color (hex) |
| `showPickups` | `boolean` | - | Show pickup count |
| `showTopApps` | `boolean` | - | Show top apps section |
| `showChart` | `boolean` | - | Show usage chart |
| `chartStyle` | `'bar' \| 'pie'` | - | Chart visualization style |

## Token Labels {#token-labels}

Display app, category, or web domain names and icons from tokens. Due to Apple's privacy design, token names cannot be extracted programmatically — they can only be rendered visually.

```typescript
import { ApplicationLabel, CategoryLabel, WebDomainLabel } from 'expo-screen-time';

// Display an app's name and icon
<ApplicationLabel
  token={selection.applicationTokens[0]}
  displayMode="title-and-icon"
  style={{ height: 40 }}
/>

// Display a category name
<CategoryLabel
  token={selection.categoryTokens[0]}
  displayMode="title-only"
/>

// Display a web domain
<WebDomainLabel
  token={selection.webDomainTokens[0]}
  displayMode="icon-only"
/>
```

**Display Modes:**

- `'icon-only'` — Show only the icon
- `'title-only'` — Show only the name
- `'title-and-icon'` — Show both (default)
