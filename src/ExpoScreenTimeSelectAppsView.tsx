import { requireNativeView } from 'expo';
import * as React from 'react';

import { ExpoScreenTimeSelectAppsViewProps } from './ExpoScreenTime.types';

const NativeView: React.ComponentType<ExpoScreenTimeSelectAppsViewProps> =
  requireNativeView('ExpoScreenTime');

export default function ExpoScreenTimeSelectAppsView(props: ExpoScreenTimeSelectAppsViewProps) {
  return <NativeView {...props} />;
}
