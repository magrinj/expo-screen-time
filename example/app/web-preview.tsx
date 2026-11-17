import React, { useEffect, useState } from "react";
import { Button, ScrollView, Text, TextInput } from "react-native";
import ScreenTime from "expo-screen-time";

export default function WebDomains() {
  const [domain, setDomain] = useState("example.com");
  const [result, setResult] = useState("Ready");
  const [hasVpn, setHasVpn] = useState(false);
  const [steps, setSteps] = useState("[]");
  const [domains, setDomains] = useState("[]");
  const [gaps, setGaps] = useState("[]");
  const supported = ScreenTime.getCapabilities().webDomainBlocking;
  const refresh = async () => {
    if (!supported) { setResult("Web domain blocking is not included in this build."); return; }
    const permissions = await ScreenTime.getScreenTimePermissionsAsync({ features: ["webDomainBlocking"] });
    setHasVpn(permissions.steps.some((step) => step.id === "vpn"));
    setSteps(JSON.stringify(permissions.steps));
    setGaps(JSON.stringify(await ScreenTime.getEnforcementGaps()));
    setDomains(JSON.stringify(ScreenTime.getBlockedWebDomains()));
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
    <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ padding: 20, gap: 12 }}>
      <Text>Web domain blocking</Text>
      {hasVpn && <Text>
        This filter uses Android’s VPN slot to inspect DNS requests and block the domains
        you choose. It replaces another VPN. Web traffic is not proxied, and the host app
        is excluded. Tap Request VPN only if you agree.
      </Text>}
      <TextInput accessibilityLabel="Web domain" value={domain} onChangeText={setDomain} autoCapitalize="none" style={{ borderWidth: 1, padding: 8 }} />
      <Button disabled={!supported} title="Block domain" onPress={() => void run(() => ScreenTime.blockWebDomains([domain]))} />
      <Button disabled={!supported} title="Unblock domain" onPress={() => void run(() => ScreenTime.unblockWebDomains([domain]))} />
      <Button disabled={!supported} title="Unblock all domains" onPress={() => void run(() => ScreenTime.unblockAllWebDomains())} />
      {hasVpn && <Button disabled={!supported} title="Request VPN" onPress={() => void run(() => ScreenTime.requestScreenTimePermissionsAsync({ features: ["webDomainBlocking"], step: "vpn" }))} />}
      <Button title="Read filter state" onPress={() => void run(() => undefined)} />
      <Text accessibilityLabel={`Web result: ${result}`}>Result: {result}</Text>
      <Text accessibilityLabel={`Web steps: ${steps}`}>Steps: {steps}</Text>
      <Text accessibilityLabel={`Web domains: ${domains}`}>Domains: {domains}</Text>
      <Text accessibilityLabel={`Web gaps: ${gaps}`}>Gaps: {gaps}</Text>
    </ScrollView>
  );
}
