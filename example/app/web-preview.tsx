import React, { useEffect, useState } from "react";
import { Button, ScrollView, Text, TextInput } from "react-native";
import ScreenTime from "expo-screen-time";

type PreviewOptions = { features: ["webDomainBlocking"]; step?: "vpn" };
type PreviewStep = { id: string; status: string };
type PreviewGap = { from: number; to: number | null; reason: string; scope?: "webDomains" };

const preview = ScreenTime as unknown as {
  blockWebDomains(domains: string[]): void;
  unblockWebDomains(domains: string[]): void;
  unblockAllWebDomains(): void;
  getBlockedWebDomains(): string[];
  getScreenTimePermissionsAsync(options: PreviewOptions): Promise<{ steps: PreviewStep[] }>;
  requestScreenTimePermissionsAsync(options: PreviewOptions): Promise<{ steps: PreviewStep[] }>;
  getEnforcementGaps(since?: number): Promise<PreviewGap[]>;
};

export default function WebPreview() {
  const [domain, setDomain] = useState("example.com");
  const [result, setResult] = useState("Ready");
  const [steps, setSteps] = useState("[]");
  const [domains, setDomains] = useState("[]");
  const [gaps, setGaps] = useState("[]");
  const refresh = async () => {
    const permissions = await preview.getScreenTimePermissionsAsync({ features: ["webDomainBlocking"] });
    setSteps(JSON.stringify(permissions.steps));
    setGaps(JSON.stringify(await preview.getEnforcementGaps()));
    setDomains(JSON.stringify(preview.getBlockedWebDomains()));
  };
  const run = async (operation: () => unknown | Promise<unknown>) => {
    try {
      const value = await operation();
      setResult(JSON.stringify(value ?? "OK"));
      await refresh();
    } catch (error) {
      setResult(String(error));
    }
  };
  useEffect(() => { void run(() => undefined); }, []);

  return (
    <ScrollView contentContainerStyle={{ padding: 20, gap: 12 }}>
      <Text>Internal web filter preview</Text>
      <Text>
        This preview uses Android’s VPN slot to inspect DNS requests and block the domains
        you choose. It replaces another VPN. Web traffic is not proxied, and the host app
        is excluded. Tap Request VPN only if you agree.
      </Text>
      <TextInput accessibilityLabel="Web domain" value={domain} onChangeText={setDomain} autoCapitalize="none" style={{ borderWidth: 1, padding: 8 }} />
      <Button title="Block domain" onPress={() => void run(() => preview.blockWebDomains([domain]))} />
      <Button title="Unblock domain" onPress={() => void run(() => preview.unblockWebDomains([domain]))} />
      <Button title="Unblock all domains" onPress={() => void run(() => preview.unblockAllWebDomains())} />
      <Button title="Request VPN" onPress={() => void run(() => preview.requestScreenTimePermissionsAsync({ features: ["webDomainBlocking"], step: "vpn" }))} />
      <Button title="Read preview state" onPress={() => void run(() => undefined)} />
      <Text accessibilityLabel={`Web result: ${result}`}>Result: {result}</Text>
      <Text accessibilityLabel={`Web steps: ${steps}`}>Steps: {steps}</Text>
      <Text accessibilityLabel={`Web domains: ${domains}`}>Domains: {domains}</Text>
      <Text accessibilityLabel={`Web gaps: ${gaps}`}>Gaps: {gaps}</Text>
    </ScrollView>
  );
}
