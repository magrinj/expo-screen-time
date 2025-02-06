import {
  createPermissionHook,
} from 'expo-modules-core';
import ExpoScreenTimeModule from './ExpoScreenTimeModule';


export const useScreenTimePermissions = createPermissionHook({
  requestMethod: ExpoScreenTimeModule.requestScreenTimePermissionsAsync,
  getMethod: ExpoScreenTimeModule.getScreenTimePermissionsAsync,
});

// Reexport the native module. On web, it will be resolved to ExpoScreenTimeModule.web.ts
// and on native platforms to ExpoScreenTimeModule.ts
export { default } from './ExpoScreenTimeModule';
export { default as ExpoScreenTimeActivityPicker } from './ExpoScreenTimeActivityPickerView';
export * from  './ExpoScreenTime.types';