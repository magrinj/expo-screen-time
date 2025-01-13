import * as React from 'react';

import { ExpoScreenTimeViewProps } from './ExpoScreenTime.types';

export default function ExpoScreenTimeView(props: ExpoScreenTimeViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
