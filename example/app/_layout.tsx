import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import React from "react";
import {
  getTokens,
  getSelectionMetadata,
  type TokenHandle,
  type SelectionMetadata,
} from "expo-screen-time";
import { useCallback, useEffect, useState } from "react";

/** Consistent selection ID used throughout the app */
export const SELECTION_ID = "blocked_apps";

/**
 * Hook that loads selection state from the native SelectionStore.
 * Call `refresh()` after any mutation (block, unblock, pick).
 */
export function useNativeSelection(selectionId: string) {
  const [tokens, setTokens] = useState<TokenHandle[]>([]);
  const [metadata, setMetadata] = useState<SelectionMetadata | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      const [meta, handles] = await Promise.all([
        getSelectionMetadata(selectionId),
        getTokens(selectionId),
      ]);
      setMetadata(meta ?? null);
      setTokens(handles ?? []);
    } catch (e) {
      console.error("Failed to load selection:", e);
    } finally {
      setLoading(false);
    }
  }, [selectionId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { tokens, metadata, loading, refresh };
}

export default function RootLayout() {
  return (
    <>
      <Stack>
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen name="conformance" options={{ title: "Conformance" }} />
        <Stack.Screen
          name="select-apps"
          options={{
            presentation: "modal",
          }}
        />
      </Stack>
      <StatusBar style="auto" />
    </>
  );
}
