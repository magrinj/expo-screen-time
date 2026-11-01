---
id: permissions
title: Permissions
sidebar_position: 2
---

# Permissions

## `requestScreenTimePermissionsAsync(options?)`

Request Family Controls authorization from the user. On iOS 16 and later it
never rejects: a failed request resolves with the resulting status, and the
reason in the step's `error`. Earlier iOS versions reject with `UNSUPPORTED`.

```typescript
const response = await ExpoScreenTime.requestScreenTimePermissionsAsync();
// response.granted: boolean
// response.status: 'granted' | 'denied' | 'undetermined'
// response.canAskAgain: boolean
// response.steps: [{ id: 'screenTime', status, required: true, kind: 'prompt', error? }]
```

### Options

| Option | Type | Default | |
|---|---|---|---|
| `member` | `'individual' \| 'child'` | `'individual'` | `child` requests Family Sharing authorization for a child account, approved by a parent. On an account that is not a child in a family, the request fails with the step error `invalidAccountType`. |

```typescript
await ExpoScreenTime.requestScreenTimePermissionsAsync({ member: "child" });
```

The authorization status is device-wide: once granted, by either member, later
requests resolve at once without a prompt.

### Steps and errors

`steps` lists what the user must allow. On iOS there is one step, `screenTime`
(the system prompt). Its `status` adds two values to Expo's:

- `restricted`: the OS refuses to let this user grant access (Screen Time
  restrictions, a managed device). The response then reports `status: 'denied'`
  and `canAskAgain: false`; the user has to change the restriction in Settings.
- `unknown`: the status cannot be read back.

`error` is present when the last request failed: `code` is the
`FamilyControlsError` case name (`restricted`, `invalidAccountType`,
`authorizationCanceled`, `networkError`, `unavailable`, …) and `message` its
description. It disappears once access is granted, and is kept for the app's
lifetime only.

```typescript
const response = await ExpoScreenTime.requestScreenTimePermissionsAsync();
const step = response.steps.find((s) => s.id === "screenTime");
if (step?.status === "restricted") {
  // explain how to lift the restriction in Settings › Screen Time
} else if (step?.error?.code === "networkError") {
  // offer to retry
}
```

## `getScreenTimePermissionsAsync(options?)`

Check the current authorization status without prompting. Same response as
`requestScreenTimePermissionsAsync`; `options` are accepted for symmetry and
ignored.

```typescript
const response = await ExpoScreenTime.getScreenTimePermissionsAsync();
```

## `useScreenTimePermissions(options?)` Hook

Expo-style permissions hook for React components. `options` takes `member`,
plus the hook's own `get` and `request` flags.

```typescript
function MyComponent() {
  const [permission, requestPermission] = useScreenTimePermissions();

  if (!permission?.granted) {
    return <Button title="Grant Access" onPress={requestPermission} />;
  }

  return <Text>Permission granted!</Text>;
}
```
