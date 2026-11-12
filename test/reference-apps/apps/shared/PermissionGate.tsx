import {
  requestScreenTimePermissionsAsync,
  useScreenTimePermissions,
  type PermissionStep,
  type PermissionStepId,
  type ScreenTimePermissionOptions,
} from "expo-screen-time";
import type { ReactNode } from "react";
import {
  ActivityIndicator,
  Button,
  ScrollView,
  Text,
  View,
} from "react-native";

/**
 * The library says WHICH steps this device needs; the app says WHY. One table
 * covers both platforms — iOS simply never lists the Android step ids.
 */
const STEP_COPY: Record<PermissionStepId, { title: string; why: string }> = {
  screenTime: {
    title: "Screen Time access",
    why: "Lets us shield the apps you choose.",
  },
  usageAccess: {
    title: "Usage access",
    why: "Lets us see which app just opened, so we can shield it.",
  },
  overlay: {
    title: "Display over other apps",
    why: "Lets us show the pause screen on top of a blocked app.",
  },
  accessibility: {
    title: "Accessibility",
    why: "Detects the app you open, instantly. We never read what is on screen.",
  },
  notifications: {
    title: "Notifications",
    why: "Shows that protection is on, and brings you back here from the shield.",
  },
  batteryOptimization: {
    title: "Battery optimization",
    why: "Stops the system from pausing protection in the background.",
  },
  backgroundAutostart: {
    title: "Autostart",
    why: "Your phone's maker may stop background apps. Allow us to restart.",
  },
  deviceAdmin: {
    title: "Device admin",
    why: "Makes removing the app a deliberate, two-step action.",
  },
};

const ACTION_LABEL: Record<PermissionStep["kind"], string> = {
  prompt: "Allow",
  settings: "Open settings",
  guide: "Show me how",
};

type Props = ScreenTimePermissionOptions & { children: ReactNode };

/**
 * Onboarding checklist until every required step is granted, then the app,
 * with the recommended (reliability) steps offered underneath.
 */
export function PermissionGate({ children, ...options }: Props) {
  const [permission, , refresh] = useScreenTimePermissions(options);
  if (!permission) return <ActivityIndicator />;

  const request = async (step: PermissionStepId) => {
    await requestScreenTimePermissionsAsync({ ...options, step });
    await refresh();
  };
  const pending = permission.steps.filter((step) => step.status !== "granted");

  if (!permission.granted) {
    return (
      <StepList
        title="Set up protection"
        steps={pending.filter((step) => step.required)}
        onRequest={request}
      />
    );
  }

  const recommended = pending.filter((step) => !step.required);
  return (
    <View style={{ flex: 1 }}>
      {children}
      {recommended.length > 0 && (
        <StepList
          title="Keep protection running"
          steps={recommended}
          onRequest={request}
        />
      )}
    </View>
  );
}

function StepList({
  title,
  steps,
  onRequest,
}: {
  title: string;
  steps: PermissionStep[];
  onRequest: (step: PermissionStepId) => void;
}) {
  return (
    <ScrollView contentContainerStyle={{ padding: 24, gap: 16 }}>
      <Text style={{ fontSize: 22, fontWeight: "700" }}>{title}</Text>
      {steps.map((step) => (
        <View key={step.id} style={{ gap: 4 }}>
          <Text style={{ fontWeight: "600" }}>{STEP_COPY[step.id].title}</Text>
          <Text>{STEP_COPY[step.id].why}</Text>
          <Button
            title={ACTION_LABEL[step.kind]}
            onPress={() => onRequest(step.id)}
          />
        </View>
      ))}
    </ScrollView>
  );
}
