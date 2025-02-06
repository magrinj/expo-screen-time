import { PermissionResponse } from "expo-modules-core";
import { Link } from "expo-router";
import ExpoScreenTime from "expo-screen-time";
import { useAtomValue } from "jotai";
import { useEffect, useState } from "react";
import { Button, SafeAreaView, ScrollView, Text, View } from "react-native";

import { selectionAtom } from "../_layout";

export default function App() {
  const selection = useAtomValue(selectionAtom);

  const [permissions, setPermissions] = useState<PermissionResponse | null>(
    null,
  );

  useEffect(() => {
    console.log("getPermissionsAsync");
    ExpoScreenTime.getScreenTimePermissionsAsync().then((permissions) => {
      console.log("permissions", permissions);
      setPermissions(permissions);
    });
  }, []);

  console.log("selection?.applicationTokens: ", selection, typeof selection);

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView style={styles.container}>
        <Text style={styles.header}>Screen Time Example</Text>
        <Group name="Permissions">
          <Text>{JSON.stringify(permissions)}</Text>
        </Group>
        <Group name="Async functions">
          <Button
            title="Request permissions"
            onPress={async () => {
              const res =
                await ExpoScreenTime.requestScreenTimePermissionsAsync();
              console.log("res", res);
            }}
          />
          <Button
            title="Display activity picker"
            onPress={async () => {
              await ExpoScreenTime.displayActivityPickerAsync();
            }}
          />
        </Group>
        <Group name="Deny app removal">
          <Button
            title="Deny app removal"
            onPress={async () => {
              ExpoScreenTime.denyAppRemoval();
            }}
          />
          <Button
            title="Allow app removal"
            onPress={async () => {
              ExpoScreenTime.allowAppRemoval();
            }}
          />
        </Group>
        <Group name="Block selection">
          <Button
            title="Block selection"
            onPress={async () => {
              if (selection) {
                ExpoScreenTime.blockSelection(selection);
              }
            }}
          />
          <Button
            title="Unblock all"
            onPress={async () => {
              ExpoScreenTime.unblockAll();
            }}
          />
        </Group>
        {/* <Group name="AppIconView">
          {selection?.applicationTokens?.map((token: string) => (
            <ExpoScreenTimeAppIconView
              style={styles.appIcon}
              key={token}
              token={token}
              tokenType="application"
            />
          ))}
        </Group> */}
        <Group name="Select Apps">
          <Link href="/select-apps">
            <Text>Select Apps</Text>
          </Link>
        </Group>
      </ScrollView>
    </SafeAreaView>
  );
}

function Group(props: { name: string; children: React.ReactNode }) {
  return (
    <View style={styles.group}>
      <Text style={styles.groupHeader}>{props.name}</Text>
      {props.children}
    </View>
  );
}

const styles = {
  header: {
    fontSize: 30,
    margin: 20,
  },
  groupHeader: {
    fontSize: 20,
    marginBottom: 20,
  },
  group: {
    margin: 20,
    backgroundColor: "#fff",
    borderRadius: 10,
    padding: 20,
  },
  container: {
    flex: 1,
    backgroundColor: "#eee",
  },
  view: {
    flex: 1,
    height: 200,
  },
  appIcon: {
    width: 300,
    height: 300,
  },
};
