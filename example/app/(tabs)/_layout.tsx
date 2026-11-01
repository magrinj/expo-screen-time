import { Ionicons } from "@expo/vector-icons";
import { Tabs } from "expo-router";
import React from "react";

// expo-router 58 types the tab icon color as RN 0.88's ColorValue (which admits
// null); @expo/vector-icons 15 accepts string | OpaqueColorValue. It is a string
// at runtime.

export default function TabLayout() {
  return (
    <Tabs
      screenOptions={{
        headerShown: true,
        tabBarActiveTintColor: "#007AFF",
        tabBarInactiveTintColor: "#8E8E93",
        tabBarStyle: {
          backgroundColor: "#F2F2F7",
          borderTopColor: "#C6C6C8",
        },
        headerStyle: {
          backgroundColor: "#F2F2F7",
        },
        headerTitleStyle: {
          fontWeight: "600",
        },
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: "Home",
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="home" size={size} color={color as string} />
          ),
        }}
      />
      <Tabs.Screen
        name="analytics"
        options={{
          title: "Analytics",
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="bar-chart" size={size} color={color as string} />
          ),
        }}
      />
      <Tabs.Screen
        name="blocking"
        options={{
          title: "Blocking",
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="shield" size={size} color={color as string} />
          ),
        }}
      />
      <Tabs.Screen
        name="scheduling"
        options={{
          title: "Schedule",
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="time" size={size} color={color as string} />
          ),
        }}
      />
      <Tabs.Screen
        name="labels"
        options={{
          title: "Labels",
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="pricetag" size={size} color={color as string} />
          ),
        }}
      />
      <Tabs.Screen
        name="settings"
        options={{
          title: "Settings",
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="settings" size={size} color={color as string} />
          ),
        }}
      />
    </Tabs>
  );
}
