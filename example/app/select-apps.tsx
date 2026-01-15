import { ActivityPicker } from "expo-screen-time";
import { StyleSheet } from "react-native";

import { SELECTION_ID } from "./_layout";

export default function SelectApps() {
  return (
    <ActivityPicker
      style={styles.picker}
      selectionId={SELECTION_ID}
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
