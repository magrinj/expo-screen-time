import { Stack } from "expo-router";
import { FamilyActivitySelection } from "expo-screen-time";
import { StatusBar } from 'expo-status-bar';
import { atom } from "jotai";
import React from 'react';

export const selectionAtom = atom<FamilyActivitySelection | null>(null);

export default function RootLayout() {
  return (
    <>
      <Stack>
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
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

