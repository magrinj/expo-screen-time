import { requireNativeView } from "expo";
import * as React from "react";

import type { ExpoScreenTimeActivityPickerProps } from "./ExpoScreenTime.types";

const NativeView: React.ComponentType<ExpoScreenTimeActivityPickerProps> =
  requireNativeView("ExpoScreenTime");

export default function ActivityPicker(
  props: ExpoScreenTimeActivityPickerProps,
) {
  return <NativeView {...props} />;
}
