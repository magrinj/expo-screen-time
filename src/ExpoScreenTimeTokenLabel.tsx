import { requireNativeView } from "expo";
import * as React from "react";

import type { TokenLabelProps } from "./ExpoScreenTime.types";

const NativeView: React.ComponentType<TokenLabelProps> = requireNativeView(
  "ExpoScreenTime",
  "ExpoScreenTimeTokenLabelView",
);

/**
 * Renders an app/category/domain icon and/or name from a token handle.
 * The actual token data stays on the native side — only the handle crosses the bridge.
 * `selectionId` is optional: a handle resolves on its own, so a handle from
 * `getTokens` or the picker renders even once persisted on its own.
 * @platform ios
 */
export default function TokenLabel(props: TokenLabelProps) {
  return <NativeView {...props} />;
}

type ConvenienceLabelProps = Omit<TokenLabelProps, "tokenType">;

export function ApplicationLabel({
  selectionId,
  handle,
  style,
  displayMode,
}: ConvenienceLabelProps) {
  return (
    <TokenLabel
      tokenType="application"
      selectionId={selectionId}
      handle={handle}
      style={style}
      displayMode={displayMode}
    />
  );
}

export function CategoryLabel({
  selectionId,
  handle,
  style,
  displayMode,
}: ConvenienceLabelProps) {
  return (
    <TokenLabel
      tokenType="category"
      selectionId={selectionId}
      handle={handle}
      style={style}
      displayMode={displayMode}
    />
  );
}

export function WebDomainLabel({
  selectionId,
  handle,
  style,
  displayMode,
}: ConvenienceLabelProps) {
  return (
    <TokenLabel
      tokenType="webDomain"
      selectionId={selectionId}
      handle={handle}
      style={style}
      displayMode={displayMode}
    />
  );
}
