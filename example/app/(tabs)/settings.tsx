import { Ionicons } from "@expo/vector-icons";
import ExpoScreenTime from "expo-screen-time";
import { useState } from "react";
import { Alert, ScrollView, StyleSheet, Switch, Text, View } from "react-native";

import {
  ActionButton,
  Card,
  colors,
  Row,
  SectionHeader,
  Separator,
} from "../components/ui";

// Example domains for testing (safe, non-adult content)
const EXAMPLE_DOMAINS = [
  { domain: "example.com", label: "Example Domain" },
  { domain: "test-domain.org", label: "Test Domain" },
  { domain: "blocked-site.net", label: "Blocked Site" },
];

export default function SettingsScreen() {
  const [appRemovalDenied, setAppRemovalDenied] = useState(false);
  const [appInstallationDenied, setAppInstallationDenied] = useState(false);
  // Start from what the module persisted, so the toggles survive relaunches.
  const [blockedDomains, setBlockedDomains] = useState<string[]>(() =>
    ExpoScreenTime.getBlockedWebDomains(),
  );

  const handleAppRemovalToggle = (value: boolean) => {
    try {
      if (value) {
        ExpoScreenTime.denyAppRemoval();
      } else {
        ExpoScreenTime.allowAppRemoval();
      }
      setAppRemovalDenied(value);
    } catch (error) {
      Alert.alert("Error", "Failed to update setting");
    }
  };

  const handleAppInstallationToggle = (value: boolean) => {
    try {
      if (value) {
        ExpoScreenTime.denyAppInstallation();
      } else {
        ExpoScreenTime.allowAppInstallation();
      }
      setAppInstallationDenied(value);
    } catch (error) {
      Alert.alert("Error", "Failed to update setting");
    }
  };

  const toggleDomain = (domain: string) => {
    try {
      if (blockedDomains.includes(domain)) {
        // Unblock this specific domain
        ExpoScreenTime.unblockWebDomains([domain]);
        setBlockedDomains((prev) => prev.filter((d) => d !== domain));
      } else {
        // Block this domain
        ExpoScreenTime.blockWebDomains([domain]);
        setBlockedDomains((prev) => [...prev, domain]);
      }
    } catch (error) {
      Alert.alert("Error", "Failed to update domain blocking");
    }
  };

  const handleBlockAllDomains = () => {
    try {
      const allDomains = EXAMPLE_DOMAINS.map((d) => d.domain);
      ExpoScreenTime.blockWebDomains(allDomains);
      setBlockedDomains(allDomains);
    } catch (error) {
      Alert.alert("Error", "Failed to block domains");
    }
  };

  const handleUnblockAllDomains = () => {
    try {
      ExpoScreenTime.unblockAllWebDomains();
      setBlockedDomains([]);
    } catch (error) {
      Alert.alert("Error", "Failed to unblock domains");
    }
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* App Store Controls */}
      <SectionHeader title="App Store Controls" />
      <Card>
        <View style={styles.settingRow}>
          <View style={styles.settingIcon}>
            <Ionicons name="trash" size={20} color={colors.danger} />
          </View>
          <View style={styles.settingContent}>
            <Text style={styles.settingTitle}>Deny App Removal</Text>
            <Text style={styles.settingDescription}>
              Prevent users from uninstalling apps
            </Text>
          </View>
          <Switch
            value={appRemovalDenied}
            onValueChange={handleAppRemovalToggle}
            trackColor={{ false: colors.separator, true: colors.danger }}
            thumbColor="#FFFFFF"
          />
        </View>
        <Separator />
        <View style={styles.settingRow}>
          <View style={styles.settingIcon}>
            <Ionicons name="download" size={20} color={colors.warning} />
          </View>
          <View style={styles.settingContent}>
            <Text style={styles.settingTitle}>Deny App Installation</Text>
            <Text style={styles.settingDescription}>
              Prevent users from installing new apps
            </Text>
          </View>
          <Switch
            value={appInstallationDenied}
            onValueChange={handleAppInstallationToggle}
            trackColor={{ false: colors.separator, true: colors.warning }}
            thumbColor="#FFFFFF"
          />
        </View>
      </Card>

      {/* Web Domain Blocking */}
      <SectionHeader title="Web Domain Blocking" />
      <Card>
        <View style={styles.domainInfo}>
          <Ionicons name="globe" size={24} color={colors.primary} />
          <Text style={styles.domainInfoText}>
            Block specific web domains by their URL. Tap each domain to toggle
            blocking, or use the buttons below to block/unblock all.
          </Text>
        </View>
        <Separator />
        <View style={styles.domainList}>
          <Text style={styles.domainListTitle}>
            Example Domains ({blockedDomains.length}/{EXAMPLE_DOMAINS.length} blocked):
          </Text>
          {EXAMPLE_DOMAINS.map(({ domain, label }) => {
            const isBlocked = blockedDomains.includes(domain);
            return (
              <View key={domain} style={styles.domainRow}>
                <View style={styles.domainItem}>
                  <Ionicons
                    name={isBlocked ? "close-circle" : "ellipse-outline"}
                    size={18}
                    color={isBlocked ? colors.danger : colors.textTertiary}
                  />
                  <View style={styles.domainTextContainer}>
                    <Text
                      style={[styles.domainText, isBlocked && styles.domainTextBlocked]}
                    >
                      {domain}
                    </Text>
                    <Text style={styles.domainLabel}>{label}</Text>
                  </View>
                </View>
                <Switch
                  value={isBlocked}
                  onValueChange={() => toggleDomain(domain)}
                  trackColor={{ false: colors.separator, true: colors.danger }}
                  thumbColor="#FFFFFF"
                />
              </View>
            );
          })}
        </View>
      </Card>

      <View style={styles.buttonRow}>
        <ActionButton
          title="Block All"
          icon="lock-closed"
          color={colors.danger}
          onPress={handleBlockAllDomains}
        />
        <ActionButton
          title="Unblock All"
          icon="lock-open"
          color={colors.success}
          onPress={handleUnblockAllDomains}
        />
      </View>

      {/* Code Example */}
      <Card>
        <View style={styles.codeBlock}>
          <Text style={styles.codeText}>
            {`// Block specific domains (adds to existing)
ExpoScreenTime.blockWebDomains([
  "facebook.com",
  "instagram.com",
]);

// Unblock specific domains
ExpoScreenTime.unblockWebDomains([
  "facebook.com",
]);

// Unblock all custom domain blocks
ExpoScreenTime.unblockAllWebDomains();

// Get currently blocked domains
const blocked = ExpoScreenTime.getBlockedWebDomains();`}
          </Text>
        </View>
      </Card>

      {/* API Reference */}
      <SectionHeader title="Available APIs" />
      <Card>
        <View style={styles.apiSection}>
          <Text style={styles.apiCategory}>Permissions</Text>
          <Text style={styles.apiItem}>• requestScreenTimePermissionsAsync()</Text>
          <Text style={styles.apiItem}>• getScreenTimePermissionsAsync()</Text>
          <Text style={styles.apiItem}>• useScreenTimePermissions()</Text>
        </View>
        <Separator />
        <View style={styles.apiSection}>
          <Text style={styles.apiCategory}>Blocking</Text>
          <Text style={styles.apiItem}>• blockSelection(selectionId)</Text>
          <Text style={styles.apiItem}>• unblockAll()</Text>
          <Text style={styles.apiItem}>• blockWebDomains(domains[])</Text>
          <Text style={styles.apiItem}>• unblockWebDomains(domains[])</Text>
          <Text style={styles.apiItem}>• unblockAllWebDomains()</Text>
          <Text style={styles.apiItem}>• getBlockedWebDomains()</Text>
        </View>
        <Separator />
        <View style={styles.apiSection}>
          <Text style={styles.apiCategory}>App Store Controls</Text>
          <Text style={styles.apiItem}>• denyAppRemoval() / allowAppRemoval()</Text>
          <Text style={styles.apiItem}>
            • denyAppInstallation() / allowAppInstallation()
          </Text>
        </View>
        <Separator />
        <View style={styles.apiSection}>
          <Text style={styles.apiCategory}>Selection Store</Text>
          <Text style={styles.apiItem}>• listSelections()</Text>
          <Text style={styles.apiItem}>• getSelectionMetadata(selectionId)</Text>
          <Text style={styles.apiItem}>• getTokens(selectionId)</Text>
          <Text style={styles.apiItem}>• deleteSelection(selectionId)</Text>
          <Text style={styles.apiItem}>• exportSelection / importSelection</Text>
        </View>
        <Separator />
        <View style={styles.apiSection}>
          <Text style={styles.apiCategory}>Components</Text>
          <Text style={styles.apiItem}>• ActivityPicker (selectionId prop)</Text>
          <Text style={styles.apiItem}>• ApplicationLabel</Text>
          <Text style={styles.apiItem}>• CategoryLabel</Text>
          <Text style={styles.apiItem}>• WebDomainLabel</Text>
          <Text style={styles.apiItem}>• TokenLabel</Text>
        </View>
      </Card>

      {/* Info Section */}
      <SectionHeader title="Framework Information" />
      <Card>
        <Row
          icon="information-circle"
          iconColor={colors.primary}
          title="About Screen Time API"
          subtitle="iOS 15.0+ required"
        />
        <Separator />
        <Row
          icon="logo-apple"
          iconColor="#000000"
          title="FamilyControls Framework"
          subtitle="Parental control features"
        />
        <Separator />
        <Row
          icon="shield"
          iconColor={colors.success}
          title="ManagedSettings Framework"
          subtitle="App shielding and restrictions"
        />
      </Card>

      {/* Limitations */}
      <SectionHeader title="Known Limitations" />
      <Card>
        <View style={styles.limitationSection}>
          <View style={styles.limitationItem}>
            <Ionicons name="close-circle" size={18} color={colors.danger} />
            <Text style={styles.limitationText}>
              Cannot extract app names or bundle IDs from tokens (privacy by design)
            </Text>
          </View>
          <View style={styles.limitationItem}>
            <Ionicons name="close-circle" size={18} color={colors.danger} />
            <Text style={styles.limitationText}>
              Cannot customize label font color or icon size (Apple restriction)
            </Text>
          </View>
          <View style={styles.limitationItem}>
            <Ionicons name="close-circle" size={18} color={colors.danger} />
            <Text style={styles.limitationText}>
              Usage data cannot be accessed from JavaScript (sandbox restriction)
            </Text>
          </View>
        </View>
      </Card>

      <View style={styles.footer} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  content: {
    paddingBottom: 100,
  },
  settingRow: {
    flexDirection: "row",
    alignItems: "center",
    padding: 16,
  },
  settingIcon: {
    width: 36,
    height: 36,
    borderRadius: 8,
    backgroundColor: colors.background,
    justifyContent: "center",
    alignItems: "center",
    marginRight: 12,
  },
  settingContent: {
    flex: 1,
  },
  settingTitle: {
    fontSize: 17,
    color: colors.text,
    marginBottom: 2,
  },
  settingDescription: {
    fontSize: 13,
    color: colors.textTertiary,
  },
  domainInfo: {
    flexDirection: "row",
    padding: 16,
    alignItems: "flex-start",
  },
  domainInfoText: {
    flex: 1,
    fontSize: 14,
    color: colors.textSecondary,
    marginLeft: 12,
    lineHeight: 20,
  },
  domainList: {
    padding: 16,
    paddingTop: 8,
  },
  domainListTitle: {
    fontSize: 13,
    fontWeight: "600",
    color: colors.textTertiary,
    marginBottom: 10,
  },
  domainRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingVertical: 8,
  },
  domainItem: {
    flexDirection: "row",
    alignItems: "center",
    flex: 1,
  },
  domainTextContainer: {
    marginLeft: 10,
    flex: 1,
  },
  domainText: {
    fontSize: 15,
    fontFamily: "Menlo",
    color: colors.text,
  },
  domainTextBlocked: {
    textDecorationLine: "line-through",
    color: colors.danger,
  },
  domainLabel: {
    fontSize: 12,
    color: colors.textTertiary,
    marginTop: 2,
  },
  buttonRow: {
    flexDirection: "row",
    paddingHorizontal: 8,
  },
  codeBlock: {
    backgroundColor: "#1E1E1E",
    borderRadius: 8,
    margin: 16,
    padding: 16,
  },
  codeText: {
    fontFamily: "Menlo",
    fontSize: 11,
    color: "#D4D4D4",
    lineHeight: 18,
  },
  apiSection: {
    padding: 16,
  },
  apiCategory: {
    fontSize: 13,
    fontWeight: "600",
    color: colors.textTertiary,
    marginBottom: 8,
    textTransform: "uppercase",
  },
  apiItem: {
    fontSize: 14,
    fontFamily: "Menlo",
    color: colors.text,
    marginVertical: 3,
  },
  limitationSection: {
    padding: 16,
  },
  limitationItem: {
    flexDirection: "row",
    alignItems: "flex-start",
    marginVertical: 6,
  },
  limitationText: {
    flex: 1,
    fontSize: 14,
    color: colors.textSecondary,
    marginLeft: 10,
    lineHeight: 20,
  },
  footer: {
    height: 20,
  },
});
