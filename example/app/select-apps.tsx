import { ApplicationSelection, ExpoScreenTimeView } from "expo-screen-time";
import { atom, useAtom } from 'jotai';

const selectionAtom = atom<ApplicationSelection | null>(null);

export default function SelectApps() {
  const [selection, setSelection] = useAtom(selectionAtom);

  console.log("SelectApps", selection);

  return (
    <ExpoScreenTimeView
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
