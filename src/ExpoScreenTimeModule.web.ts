import { registerWebModule, NativeModule } from 'expo';

import { ExpoScreenTimeModuleEvents } from './ExpoScreenTime.types';

class ExpoScreenTimeModule extends NativeModule<ExpoScreenTimeModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(ExpoScreenTimeModule);
