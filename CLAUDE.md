# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Expo Module that wraps Apple's Screen Time APIs (FamilyControls, ManagedSettings, DeviceActivity) for React Native. It enables parental control features like app blocking, activity selection, and app installation/removal restrictions.

**Note:** The iOS implementation is fully functional. The Android module is a placeholder (Screen Time APIs are iOS-only).

## Commands

```bash
# Build TypeScript
bun run build

# Lint
bun run lint

# Run tests
bun run test

# Clean build artifacts
bun run clean

# Open iOS project in Xcode
bun run open:ios

# Run example app (from example/ directory)
cd example && bun run ios
```

## Architecture

### TypeScript Layer (`src/`)
- `ExpoScreenTimeModule.ts` - Native module type declarations and JSI binding
- `ExpoScreenTime.types.ts` - TypeScript types for FamilyActivitySelection, ActivityToken, etc.
- `ExpoScreenTimeActivityPickerView.tsx` - Native view wrapper for activity picker
- `index.ts` - Public API exports including `useScreenTimePermissions` hook

### iOS Native Layer (`ios/`)
- `ExpoScreenTimeModule.swift` - Expo module definition with functions and view registration
- `ScreenTimeManager.swift` - Core logic using ManagedSettingsStore for blocking/restrictions
- `ScreenTimePermissionRequester.swift` - Handles FamilyControls authorization
- `ScreenTimeActivityPicker.swift` - SwiftUI FamilyActivityPicker wrapper
- `ScreenTimeUtils.swift` - Serialization helpers for FamilyActivitySelection tokens

### Key iOS Frameworks Used
- `FamilyControls` - Authorization and activity selection
- `ManagedSettings` - App shielding and restrictions
- `DeviceActivity` - Activity monitoring

### Example App (`example/`)
Uses expo-router for navigation. The module is auto-linked via `nativeModulesDir: ".."` in package.json.

## API Surface

**Functions:**
- `requestScreenTimePermissionsAsync()` / `getScreenTimePermissionsAsync()` - Permission handling
- `blockSelection(selection)` / `unblockAll()` - App/category/domain blocking
- `denyAppRemoval()` / `allowAppRemoval()` - Control app uninstallation
- `denyAppInstallation()` / `allowAppInstallation()` - Control app installation

**Components:**
- `ExpoScreenTimeActivityPicker` - Native view for selecting apps/categories to block

**Hooks:**
- `useScreenTimePermissions()` - Expo permissions hook
