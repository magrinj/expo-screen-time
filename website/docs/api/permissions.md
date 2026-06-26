---
id: permissions
title: Permissions
sidebar_position: 2
---

# Permissions

## `requestScreenTimePermissionsAsync()`

Request Family Controls authorization from the user.

```typescript
const response = await ExpoScreenTime.requestScreenTimePermissionsAsync();
// response.granted: boolean
// response.status: 'granted' | 'denied' | 'undetermined'
```

## `getScreenTimePermissionsAsync()`

Check current authorization status without prompting.

```typescript
const response = await ExpoScreenTime.getScreenTimePermissionsAsync();
```

## `useScreenTimePermissions()` Hook

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
