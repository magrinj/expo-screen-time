import { ExpoScreenTimeActivityPicker } from "expo-screen-time";
import { useAtom } from 'jotai';
import { selectionAtom } from "./_layout";

export default function SelectApps() {
  const [selection, setSelection] = useAtom(selectionAtom);

  console.log("SelectApps", selection);

  return (
    <ExpoScreenTimeActivityPicker
      style={styles.view}
      selection={selection}
      onSelectionChange={({ nativeEvent }) => {
        setSelection(nativeEvent.selection);
        console.log("selection", nativeEvent.selection);
      }}
    />
  );
}

const styles = {
  view: {
    flex: 1,
  },
};
