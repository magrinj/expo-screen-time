---
id: permissions
title: Permissions
sidebar_position: 2
---

# Permissions

One call site: the library reports the steps the user must allow as data, and
your onboarding explains each one. Which steps are listed depends on the
`features` you intend to use.

## `getScreenTimePermissionsAsync(options?)`

The current status of every step the requested features need, without
prompting.

```typescript
const response = await ExpoScreenTime.getScreenTimePermissionsAsync({
  features: ["blocking", "shieldOpenApp"],
});
// response.granted: boolean
// response.status: 'granted' | 'denied' | 'undetermined'
// response.canAskAgain: boolean
// response.steps: [
//   { id: 'screenTime', status, required: true, kind: 'prompt', error? },
//   { id: 'notifications', status, required: true, kind: 'prompt', error? },
// ]
```

### Options

| Option | Type | Default | |
|---|---|---|---|
| `features` | `ScreenTimeFeature[]` | `['blocking', 'monitoring']` | What the app will use; decides which steps are listed and required. On iOS every feature rests on the `screenTime` step; `shieldOpenApp` adds the `notifications` step. On Android, `blocking` and `monitoring` need `overlay` and `usageAccess`; `usageReports` needs `usageAccess`; `appRemovalProtection` needs `deviceAdmin`. A feature the build does not contain ([`android.features`](../configuration.md#android-features)) rejects `UNSUPPORTED`, naming it; the default narrows to the default features the build has. |
| `member` | `'individual' \| 'child'` | `'individual'` | `child` requests Family Sharing authorization for a child account, approved by a parent. On an account that is not a child in a family, the request fails with the step error `invalidAccountType`. iOS reads it only when requesting `screenTime`; Android makes the `notifications` and `deviceAdmin` steps required for a child (Google Play's parental-control rules demand a persistent notice), so it is read when listing the steps too. |

### Steps on iOS

| Step | iOS | Status read from |
|---|---|---|
| `screenTime` | Always listed, required. The Family Controls system prompt. | `AuthorizationCenter` |
| `notifications` | Listed, and required, when `features` includes `shieldOpenApp`: a shield's [`openApp` action](./shields.md#open-app) reaches the app through a local notification the user taps. The prompt asks for alerts and sounds. | `UNUserNotificationCenter` (`authorized`, `provisional` and `ephemeral` are `granted`) |

### Steps on Android {#android}

Android has no single Screen Time authorization: the enforcement engine rests
on special-access toggles the user flips in Settings, one screen each. The
library lists them in onboarding order; `kind` says how each one is granted.

A step is listed only when the build has what it grants: no `overlay`
without the shield, no `deviceAdmin` without its receiver, no engine steps
without the engine (the [`android.features`](../configuration.md#android-features)
option removes them).

| Step | Listed for | Required | Kind | What it is |
|---|---|---|---|---|
| `overlay` | `blocking`, `monitoring` (in a build with the shield) | yes | `settings` | Settings › Display over other apps. The shield is an Activity the engine launches from the background, which Android allows once this is held. Requested first: it also lets the library bring the user back to the app from the next screens. |
| `usageAccess` | `blocking`, `monitoring`, `usageReports` | yes | `settings` | Settings › Usage access. How the engine sees which app is in the foreground. |
| `notifications` | `blocking`, `monitoring` (Android 13+) | for `member: 'child'` | `prompt` | The runtime notification permission for the engine's persistent notice. Without it the service still runs; the notice moves to the Task Manager. |
| `deviceAdmin` | `blocking`, `monitoring` (in a build with the device admin), `appRemovalProtection` | for `appRemovalProtection` and `member: 'child'` | `settings` | Device admin activation (no policy). While active, Settings disables "Force stop" and the app cannot be uninstalled before the admin is turned off. |
| `batteryOptimization` | `blocking`, `monitoring` | no | `settings` | The system's battery-optimization list. The library opens the list and never requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (not an acceptable use for a blocker on Google Play). |
| `backgroundAutostart` | `blocking`, `monitoring`, on Xiaomi, Oppo, Realme, Vivo, Huawei, Honor and OnePlus | no | `guide` | The vendor's autostart screen. Nothing reads it back: its status stays `unknown`. |

A settings toggle that is off reads `undetermined` until the app has sent the
user to that screen once, and `denied` after, so an onboarding UI can tell
"never asked" from "went there and came back without it". `restricted` is
reported when Android 13+ keeps the toggles locked for a sideloaded app
("restricted settings", lifted from App info), and for `overlay` on a low-RAM
device, where the system never grants it. `accessibility` and `screenTime`
are never listed on Android.

A step's `status` adds two values to Expo's:

- `restricted`: the OS refuses to let this user grant access (Screen Time
  restrictions, a managed device, Android's restricted settings). The response
  then reports `status: 'denied'` and `canAskAgain: false`; the user has to
  change the restriction in Settings.
- `unknown`: the status cannot be read back.

### The response

Expo's `PermissionResponse` plus the ordered `steps` behind it:

- `granted` is `true` when every `required` step is `granted`;
- `status` is `'granted'` if so; else `'denied'` if a required step is `denied`
  or `restricted`; else `'undetermined'`;
- `canAskAgain` is `true` when no required step is `restricted` and every
  required step still to grant can show its prompt. Family Controls shows its
  prompt again after a denial; the notification prompt is shown once, so a
  denied `notifications` step reads `canAskAgain: false` until the user allows
  it in Settings.

## `requestScreenTimePermissionsAsync(options?)`

Advances onboarding by **one** step and resolves with the whole response:
`options.step` if given, else the first required step not yet granted. Call it
again for the next step, so your UI can explain each one. A step already
granted is not asked again; when nothing is pending, nothing is prompted.

On Android a `settings` or `guide` step opens its screen and the promise
resolves when the user comes back to the app, with fresh statuses. For
`usageAccess` and `overlay` the library also watches the toggle and brings
the app back the moment it flips, which Android honours once `overlay` is
held. A screen that could not be opened (an `ActivityNotFoundException` on
an unusual device) is not an error: the step keeps it in `error` and the
response says so. A `step` the platform does not have (`screenTime`,
`accessibility`) rejects with `INVALID_CONFIG`.

On iOS 16 and later it never rejects for a refused prompt: a failed request
resolves with the resulting status, and the reason in the step's `error`. A
step whose prompt the OS will not show again (a denied `notifications` step)
is not requested: the response comes back unchanged, with `canAskAgain: false`,
and your UI should send the user to Settings. Earlier iOS versions reject
with `UNSUPPORTED`; a `step` this platform does not have (an Android id), or
one the requested `features` do not list, rejects with `INVALID_CONFIG`.

```typescript
// The first pending step (screenTime, then notifications)
let response = await ExpoScreenTime.requestScreenTimePermissionsAsync({
  features: ["blocking", "shieldOpenApp"],
});

// A specific step, from a checklist
response = await ExpoScreenTime.requestScreenTimePermissionsAsync({
  features: ["blocking", "shieldOpenApp"],
  step: "notifications",
});
```

### Options

`features` and `member` as above, plus:

| Option | Type | |
|---|---|---|
| `step` | `PermissionStepId` | The step to request. Default: the first required step not granted. |

```typescript
await ExpoScreenTime.requestScreenTimePermissionsAsync({ member: "child" });
```

The Screen Time authorization is device-wide: once granted, by either member,
later requests resolve at once without a prompt.

### Errors

`error` is present on a step when its last request failed: for `screenTime`,
`code` is the `FamilyControlsError` case name (`restricted`,
`invalidAccountType`, `authorizationCanceled`, `networkError`, `unavailable`,
…) and `message` its description; for `notifications`, an error thrown by the
request (a refusal is not an error: the step reads `denied`). It disappears
once the step is granted, and is kept for the app's lifetime only.

```typescript
const response = await ExpoScreenTime.requestScreenTimePermissionsAsync();
const step = response.steps.find((s) => s.id === "screenTime");
if (step?.status === "restricted") {
  // explain how to lift the restriction in Settings › Screen Time
} else if (step?.error?.code === "networkError") {
  // offer to retry
}
```

### An onboarding checklist

```tsx
import {
  requestScreenTimePermissionsAsync,
  useScreenTimePermissions,
  type ScreenTimePermissionOptions,
} from "expo-screen-time";
import type { ReactNode } from "react";
import { Button } from "react-native";

const options: ScreenTimePermissionOptions = {
  features: ["blocking", "shieldOpenApp"],
};

const titles: Record<string, string> = {
  screenTime: "Allow Screen Time",
  notifications: "Allow notifications",
  overlay: "Allow display over other apps",
  usageAccess: "Allow usage access",
  deviceAdmin: "Activate device admin",
  batteryOptimization: "Exempt from battery optimization",
  backgroundAutostart: "Allow autostart",
};

function PermissionGate({ children }: { children: ReactNode }) {
  const [permission, , refresh] = useScreenTimePermissions(options);
  if (!permission) return null;
  if (permission.granted) return children;

  const pending = permission.steps.filter(
    (step) => step.required && step.status !== "granted",
  );
  return (
    <>
      {pending.map((step) => (
        <Button
          key={step.id}
          title={titles[step.id] ?? step.id}
          onPress={async () => {
            await requestScreenTimePermissionsAsync({ ...options, step: step.id });
            await refresh();
          }}
        />
      ))}
    </>
  );
}
```

The same gate lists one step on iOS and several on Android, each requested
with one tap; recommended steps (`required: false`) make a good second
screen, after the app works.

## `useScreenTimePermissions(options?)` Hook

Expo-style permissions hook for React components. `options` takes `features`
and `member`, plus the hook's own `get` and `request` flags; the request
method advances the first pending step.

```typescript
function MyComponent() {
  const [permission, requestPermission] = useScreenTimePermissions();

  if (!permission?.granted) {
    return <Button title="Grant Access" onPress={requestPermission} />;
  }

  return <Text>Permission granted!</Text>;
}
```
