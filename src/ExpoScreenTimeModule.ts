import { NativeModule, requireNativeModule } from 'expo';
import { PermissionResponse } from 'expo-modules-core';

import { ExpoScreenTimeModuleEvents, FamilyActivitySelection } from './ExpoScreenTime.types';

declare class ExpoScreenTimeModule extends NativeModule<ExpoScreenTimeModuleEvents> {
  requestScreenTimePermissionsAsync(): Promise<PermissionResponse>;
  getScreenTimePermissionsAsync(): Promise<PermissionResponse>;
  denyAppRemoval(): void;
  allowAppRemoval(): void;
  denyAppInstallation(): void;
  allowAppInstallation(): void;
  blockSelection(selection: FamilyActivitySelection): void;
  unblockAll(): void;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoScreenTimeModule>('ExpoScreenTime');
