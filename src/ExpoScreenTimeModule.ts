import { NativeModule, requireNativeModule } from 'expo';
import { PermissionResponse } from 'expo-modules-core';

import { ExpoScreenTimeModuleEvents } from './ExpoScreenTime.types';

declare class ExpoScreenTimeModule extends NativeModule<ExpoScreenTimeModuleEvents> {
  requestScreenTimePermissionsAsync(): Promise<PermissionResponse>;
  getScreenTimePermissionsAsync(): Promise<PermissionResponse>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoScreenTimeModule>('ExpoScreenTime');
