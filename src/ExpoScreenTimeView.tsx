import { requireNativeView } from 'expo';
import * as React from 'react';

import { ExpoScreenTimeViewProps } from './ExpoScreenTime.types';

const NativeView: React.ComponentType<ExpoScreenTimeViewProps> =
  requireNativeView('ExpoScreenTime');

export default function ExpoScreenTimeView(props: ExpoScreenTimeViewProps) {
  return <NativeView {...props} />;
}
