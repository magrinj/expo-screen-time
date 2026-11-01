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
| `features` | `ScreenTimeFeature[]` | `['blocking', 'monitoring']` | What the app will use; decides which steps are listed and required. On iOS every feature rests on the `screenTime` step; `shieldOpenApp` adds the `notifications` step. |
| `member` | `'individual' \| 'child'` | `'individual'` | `child` requests Family Sharing authorization for a child account, approved by a parent. On an account that is not a child in a family, the request fails with the step error `invalidAccountType`. Only read when requesting `screenTime`. |

### Steps

| Step | iOS | Status read from |
|---|---|---|
| `screenTime` | Always listed, required. The Family Controls system prompt. | `AuthorizationCenter` |
| `notifications` | Listed, and required, when `features` includes `shieldOpenApp`: a shield's [`openApp` action](./shields.md#open-app) reaches the app through a local notification the user taps. The prompt asks for alerts and sounds. | `UNUserNotificationCenter` (`authorized`, `provisional` and `ephemeral` are `granted`) |

The other `PermissionStepId`s (`usageAccess`, `overlay`, `accessibility`,
`batteryOptimization`, `backgroundAutostart`, `deviceAdmin`) are Android's. They
exist in the type so shared code can label every step; iOS never lists them.

A step's `status` adds two values to Expo's:

- `restricted`: the OS refuses to let this user grant access (Screen Time
  restrictions, a managed device). The response then reports `status: 'denied'`
  and `canAskAgain: false`; the user has to change the restriction in Settings.
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
          title={step.id === "screenTime" ? "Allow Screen Time" : "Allow notifications"}
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
