import { NativeModule, requireNativeModule } from 'expo';

import { ExpoScreenTimeModuleEvents } from './ExpoScreenTime.types';

declare class ExpoScreenTimeModule extends NativeModule<ExpoScreenTimeModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoScreenTimeModule>('ExpoScreenTime');
