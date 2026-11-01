import { useLocalSearchParams } from "expo-router";
import { ActivityPicker } from "expo-screen-time";
import { StyleSheet } from "react-native";

import { SELECTION_ID } from "./_layout";

export default function SelectApps() {
  // `?id=` picks into another selection (the conformance suite's fixtures).
  const { id } = useLocalSearchParams<{ id?: string }>();
  return (
    <ActivityPicker
      style={styles.picker}
      selectionId={id ?? SELECTION_ID}
      headerText="Select apps, categories, or web domains to block"
      footerText="Selected items will be shielded when you block them"
    />
  );
}

const styles = StyleSheet.create({
  picker: {
    flex: 1,
    backgroundColor: "#F2F2F7",
  },
});
