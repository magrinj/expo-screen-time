# expo-screen-time

An Expo module that wraps Apple's Screen Time APIs (FamilyControls, ManagedSettings, DeviceActivity) for React Native. Enables parental control features like app blocking, usage monitoring, scheduled restrictions, and custom shield configurations.

> **Note:** This module is iOS-only. Screen Time APIs are not available on Android.

## Features

- **Permission Management** - Request and check Family Controls authorization
- **App Blocking** - Block apps, categories, and web domains using the native activity picker
- **Web Domain Blocking** - Programmatically block specific domains without user selection
- **App Store Restrictions** - Prevent app installation and removal
- **Schedule Monitoring** - Set up time-based blocking schedules
- **Shield Customization** - Customize the blocking screen appearance
- **Shield Actions** - Configure button behaviors (close, snooze, unblock)
- **Usage Reports** - Display device activity reports with customizable styling
- **Token Labels** - Display app/category/domain names and icons

## Requirements

- iOS 16.0+
- Expo SDK 56+
- Apple Developer account with Family Controls capability

## Installation

```bash
npm install expo-screen-time
# or
yarn add expo-screen-time
# or
bun add expo-screen-time
```

## Configuration

### Basic Setup

Add the plugins to your `app.json` or `app.config.js`:

```json
{
  "expo": {
    "ios": {
      "bundleIdentifier": "com.yourcompany.yourapp",
      "appleTeamId": "YOUR_TEAM_ID"
    },
    "plugins": ["expo-screen-time"]
  }
}
```

The `expo-screen-time` plugin automatically configures:
- **App Groups** entitlement (for sharing data between app and extensions)
- **Family Controls** entitlement
- The **iOS extension targets** (added directly to the Xcode project — no `@bacons/apple-targets` required)

> Run `npx expo prebuild --clean` after adding the plugin so the extension targets are generated.

### Plugin Options

```json
["expo-screen-time", {
  "appGroup": "group.com.yourcompany.custom"  // Optional: custom App Group identifier
}]
```

### Extension Targets

This library ships with 4 iOS extension targets that enable advanced Screen Time features:

| Target | Purpose |
|--------|---------|
| `DeviceActivityReportExtension` | Display usage analytics (screen time reports) |
| `DeviceActivityMonitorExtension` | Schedule-based monitoring and automatic blocking |
| `ShieldConfigurationExtension` | Customize the shield (blocking) screen appearance |
| `ShieldActionExtension` | Handle shield button actions (close, snooze, unblock) |

By default, all 4 targets are generated.

### Target Selection

To include only specific targets, use `expo.extra.expoScreenTime.targets`:

```json
{
  "expo": {
    "extra": {
      "expoScreenTime": {
        "targets": ["ShieldConfigurationExtension", "DeviceActivityReportExtension"]
      }
    },
    "plugins": ["expo-screen-time"]
  }
}
```

- **Omit** `targets` → all 4 targets are generated (default).
- **Empty array** `[]` → no extension targets (basic app/category blocking only).

### Using with Other Apple Targets

`expo-screen-time` adds its extension targets to the Xcode project directly via
Expo's config-plugin system, so it's self-contained and coexists with other
plugins that edit the project (including `@bacons/apple-targets` if you use it
for widgets or other extensions). No extra wiring is required.

## API Reference

### Importing

```typescript
import ExpoScreenTime, {
  useScreenTimePermissions,
  ExpoScreenTimeActivityPicker,
  DeviceActivityReport,
  ApplicationLabel,
  CategoryLabel,
  WebDomainLabel,
} from 'expo-screen-time';
```

---

### Permissions

#### `requestScreenTimePermissionsAsync()`

Request Family Controls authorization from the user.

```typescript
const response = await ExpoScreenTime.requestScreenTimePermissionsAsync();
// response.granted: boolean
// response.status: 'granted' | 'denied' | 'undetermined'
```

#### `getScreenTimePermissionsAsync()`

Check current authorization status without prompting.

```typescript
const response = await ExpoScreenTime.getScreenTimePermissionsAsync();
```

#### `useScreenTimePermissions()` Hook

Expo-style permissions hook for React components.

```typescript
function MyComponent() {
  const [permission, requestPermission] = useScreenTimePermissions();

  if (!permission?.granted) {
    return <Button title="Grant Access" onPress={requestPermission} />;
  }

  return <Text>Permission granted!</Text>;
}
```

---

### Blocking

#### `blockSelection(selection: FamilyActivitySelection)`

Block apps, categories, and web domains from a selection.

```typescript
// Selection comes from ExpoScreenTimeActivityPicker's onSelectionChange
ExpoScreenTime.blockSelection(selection);
```

#### `unblockAll()`

Remove all blocks applied via `blockSelection()`.

```typescript
ExpoScreenTime.unblockAll();
```

---

### Web Domain Blocking

#### `blockWebDomains(domains: string[])`

Programmatically block specific web domains.

```typescript
ExpoScreenTime.blockWebDomains(['facebook.com', 'instagram.com', 'tiktok.com']);
```

#### `unblockWebDomains(domains: string[])`

Unblock specific web domains.

```typescript
ExpoScreenTime.unblockWebDomains(['facebook.com']);
```

#### `unblockAllWebDomains()`

Unblock all domains blocked via `blockWebDomains()`.

```typescript
ExpoScreenTime.unblockAllWebDomains();
```

#### `getBlockedWebDomains(): string[]`

Get the list of currently blocked domain strings.

```typescript
const domains = ExpoScreenTime.getBlockedWebDomains();
// ['facebook.com', 'tiktok.com']
```

---

### App Store Restrictions

#### `denyAppInstallation()` / `allowAppInstallation()`

Prevent or allow installing apps from the App Store.

```typescript
ExpoScreenTime.denyAppInstallation();
// Later...
ExpoScreenTime.allowAppInstallation();
```

#### `denyAppRemoval()` / `allowAppRemoval()`

Prevent or allow removing apps from the device.

```typescript
ExpoScreenTime.denyAppRemoval();
// Later...
ExpoScreenTime.allowAppRemoval();
```

---

### Schedule Monitoring

> **Requires:** `DeviceActivityMonitorExtension` target

#### `startMonitoring(config: MonitoringConfiguration)`

Start monitoring device activity with a schedule. When the interval starts, blocks are activated; when it ends, blocks are deactivated.

```typescript
await ExpoScreenTime.startMonitoring({
  activityName: 'workHours',
  schedule: {
    intervalStart: { hour: 9, minute: 0 },
    intervalEnd: { hour: 17, minute: 0 },
    repeats: true,
    warningTime: 300, // 5 minute warning before interval ends
  },
  selectionToBlock: mySelection, // FamilyActivitySelection from picker
  events: [
    {
      eventName: 'socialMediaLimit',
      familyActivitySelection: socialMediaSelection,
      threshold: 3600, // 1 hour in seconds
    },
  ],
});
```

#### `stopMonitoring(activityName: string)`

Stop a specific monitoring activity.

```typescript
await ExpoScreenTime.stopMonitoring('workHours');
```

#### `stopAllMonitoring()`

Stop all active monitoring.

```typescript
await ExpoScreenTime.stopAllMonitoring();
```

#### `getMonitoredActivities(): Promise<string[]>`

Get list of currently monitored activity names.

```typescript
const activities = await ExpoScreenTime.getMonitoredActivities();
// ['workHours', 'bedtime']
```

---

### Shield Configuration

> **Requires:** `ShieldConfigurationExtension` target

#### `setShieldConfiguration(config: ShieldConfiguration)`

Set the default shield (blocking screen) appearance.

```typescript
ExpoScreenTime.setShieldConfiguration({
  backgroundColor: '#1a1a2e',
  backgroundBlurStyle: 'dark',
  icon: 'hand.raised.fill', // SF Symbol name
  title: 'App Blocked',
  titleColor: '#ffffff',
  subtitle: 'This app is restricted during work hours',
  subtitleColor: '#aaaaaa',
  primaryButtonLabel: 'OK',
  primaryButtonColor: '#ffffff',
  primaryButtonBackgroundColor: '#4a4ae8',
  secondaryButtonLabel: 'Snooze 5 min',
  secondaryButtonColor: '#ffffff',
  secondaryButtonBackgroundColor: '#666666',
});
```

#### `setShieldConfigurationForActivity(activityName: string, config: ShieldConfiguration)`

Set shield configuration for a specific monitoring activity.

```typescript
ExpoScreenTime.setShieldConfigurationForActivity('bedtime', {
  title: 'Time for Bed!',
  subtitle: 'Apps are blocked until morning',
  backgroundColor: '#0d1b2a',
});
```

#### `clearShieldConfiguration()`

Clear all shield configurations, reverting to system defaults.

```typescript
ExpoScreenTime.clearShieldConfiguration();
```

---

### Shield Actions

> **Requires:** `ShieldActionExtension` target

#### `setShieldActions(config: ShieldActionConfiguration)`

Configure what happens when shield buttons are pressed.

```typescript
ExpoScreenTime.setShieldActions({
  primaryButtonAction: 'close',        // 'close' | 'snooze' | 'unblock' | 'defer'
  secondaryButtonAction: 'snooze',
  secondaryButtonSnoozeDuration: 300,  // 5 minutes in seconds
});
```

**Action Types:**
- `close` - Close the shield, app remains blocked
- `snooze` - Temporarily unblock (use with `snoozeDuration`)
- `unblock` - Permanently unblock this specific app
- `defer` - Defer the action, allowing shield UI to update

#### `setShieldActionsForActivity(activityName: string, config: ShieldActionConfiguration)`

Set shield actions for a specific activity.

```typescript
ExpoScreenTime.setShieldActionsForActivity('workHours', {
  primaryButtonAction: 'close',
  // No secondary button for work hours
});
```

#### `clearShieldActions()`

Clear all shield action configurations.

```typescript
ExpoScreenTime.clearShieldActions();
```

---

### Monitor Events

#### `getMonitorEvents(activityName?: string, limit?: number): Promise<MonitorEvent[]>`

Get recent monitor events from storage.

```typescript
const events = await ExpoScreenTime.getMonitorEvents('workHours', 50);
// [{ activityName: 'workHours', eventType: 'intervalDidStart', timestamp: 1234567890 }]
```

**Event Types:**
- `intervalDidStart` - Scheduled interval started
- `intervalDidEnd` - Scheduled interval ended
- `eventDidReachThreshold` - Usage threshold was reached
- `intervalWillEndWarning` - Warning before interval ends
- `eventWillReachThresholdWarning` - Warning before threshold is reached

#### `clearMonitorEvents(activityName?: string)`

Clear stored monitor events.

```typescript
await ExpoScreenTime.clearMonitorEvents('workHours'); // Clear specific activity
await ExpoScreenTime.clearMonitorEvents(); // Clear all events
```

---

## Components

### `<ExpoScreenTimeActivityPicker />`

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

---

### `<DeviceActivityReport />`

> **Requires:** `DeviceActivityReportExtension` target

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

---

### Token Labels

Display app, category, or web domain names and icons from tokens. Due to Apple's privacy design, token names cannot be extracted programmatically - they can only be rendered visually.

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
- `'icon-only'` - Show only the icon
- `'title-only'` - Show only the name
- `'title-and-icon'` - Show both (default)

---

## Types

### `FamilyActivitySelection`

```typescript
type FamilyActivitySelection = {
  applicationTokens: ActivityToken[];
  categoryTokens: ActivityToken[];
  webDomainTokens: ActivityToken[];
  untokenizedApplicationIdentifiers: string[];
  untokenizedCategoryIdentifiers: string[];
  untokenizedWebDomainIdentifiers: string[];
  includeEntireCategory: boolean;
};
```

### `ActivityToken`

```typescript
type ActivityToken = {
  data: string;  // Base64-encoded token data
};
```

### `DeviceActivitySchedule`

```typescript
type DeviceActivitySchedule = {
  intervalStart: DateComponents;
  intervalEnd: DateComponents;
  repeats: boolean;
  warningTime?: number;  // seconds
};

type DateComponents = {
  hour: number;    // 0-23
  minute: number;  // 0-59
  second?: number; // 0-59
  weekday?: number; // 1 = Sunday, 7 = Saturday
};
```

### `ShieldConfiguration`

```typescript
type ShieldConfiguration = {
  backgroundColor?: string;           // hex color
  backgroundBlurStyle?: UIBlurEffectStyle;
  icon?: string;                      // SF Symbol name
  title?: string;
  titleColor?: string;
  subtitle?: string;
  subtitleColor?: string;
  primaryButtonLabel?: string;
  primaryButtonColor?: string;
  primaryButtonBackgroundColor?: string;
  secondaryButtonLabel?: string;
  secondaryButtonColor?: string;
  secondaryButtonBackgroundColor?: string;
};
```

### `ShieldActionConfiguration`

```typescript
type ShieldActionConfiguration = {
  primaryButtonAction: ShieldActionType;
  primaryButtonSnoozeDuration?: number;    // seconds
  secondaryButtonAction?: ShieldActionType;
  secondaryButtonSnoozeDuration?: number;  // seconds
};

type ShieldActionType = 'close' | 'snooze' | 'unblock' | 'defer';
```

### `MonitorEvent`

```typescript
type MonitorEvent = {
  activityName: string;
  eventType: MonitorEventType;
  eventName?: string;       // for threshold events
  timestamp: number;        // Unix milliseconds
};

type MonitorEventType =
  | 'intervalDidStart'
  | 'intervalDidEnd'
  | 'eventDidReachThreshold'
  | 'intervalWillEndWarning'
  | 'eventWillReachThresholdWarning';
```

---

## Example App

See the `example/` directory for a complete example app demonstrating all features:

```bash
cd example
bun install
bun run ios
```

---

## Troubleshooting

### "Family Controls authorization required"

The user must grant Family Controls permission before any blocking features work. Use `requestScreenTimePermissionsAsync()` to prompt for authorization.

### Extensions not appearing

1. Ensure `"expo-screen-time"` is in your `plugins` array
2. Run `npx expo prebuild --clean` to regenerate native code
3. Check that your `ios.bundleIdentifier` and `ios.appleTeamId` are set
4. If filtering targets, confirm the extension is listed in `expo.extra.expoScreenTime.targets`

### Shield configuration not applying

The `ShieldConfigurationExtension` must be included in your targets. Check your `expo.extra.expoScreenTime.targets` configuration if you're filtering targets.

### Schedule monitoring not working

The `DeviceActivityMonitorExtension` must be included. Monitor events fire when the device is first used after the schedule time (not exactly at the scheduled time).

---

## License

MIT

---

## Credits

- [expo-widgets](https://github.com/expo/expo/tree/main/packages/expo-widgets) - Expo's first-party config-plugin approach for generating Apple targets, which this library's target generation is modeled on
- [@bacons/apple-targets](https://github.com/EvanBacon/expo-apple-targets) - Evan Bacon's apple-targets plugin, the original inspiration
- Apple's [Screen Time API](https://developer.apple.com/documentation/familycontrols) documentation
