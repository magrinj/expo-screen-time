import * as ScreenTime from "expo-screen-time";
import { Button } from "react-native";

export default function BlockApps() {
  const [permission, request] = ScreenTime.useScreenTimePermissions();
  if (!permission?.granted) return <Button title="Allow" onPress={request} />;
  const block = () => ScreenTime.blockSelection("focus");
  return (
    <ScreenTime.ActivityPicker selectionId="focus" onSelectionChange={block} />
  );
}
