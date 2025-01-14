import { PermissionResponse } from 'expo-modules-core';
import ExpoScreenTime from 'expo-screen-time';
import { useEffect, useState } from 'react';
import { Button, SafeAreaView, ScrollView, Text, View } from 'react-native';

export default function App() {
  const [permissions, setPermissions] = useState<PermissionResponse | null>(null);

  useEffect(() => {
    console.log('getPermissionsAsync');
    ExpoScreenTime.getScreenTimePermissionsAsync().then((permissions) => {
      console.log('permissions', permissions);
      setPermissions(permissions);
    });
  }, []);

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
              const res = await ExpoScreenTime.requestScreenTimePermissionsAsync();
              console.log('res', res);
            }}
          />
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
    backgroundColor: '#fff',
    borderRadius: 10,
    padding: 20,
  },
  container: {
    flex: 1,
    backgroundColor: '#eee',
  },
  view: {
    flex: 1,
    height: 200,
  },
};
