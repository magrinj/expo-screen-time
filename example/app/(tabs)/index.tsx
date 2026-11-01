import { Ionicons } from "@expo/vector-icons";
import { useFocusEffect } from "expo-router";
import ExpoScreenTime, {
  ApplicationLabel,
  CategoryLabel,
  WebDomainLabel,
  type PermissionStep,
  type ScreenTimePermissionOptions,
  type ScreenTimePermissionResponse,
} from "expo-screen-time";
import { useCallback, useEffect, useState } from "react";
import { ScrollView, StyleSheet, Text, View } from "react-native";

import { SELECTION_ID, useNativeSelection } from "../_layout";
import {
  ActionButton,
  Card,
  colors,
  EmptyState,
  Row,
  SectionHeader,
  Separator,
  StatusBadge,
} from "../components/ui";

// shieldOpenApp adds the notifications step (the shield's openApp action
// posts a notification), so both steps show up in the checklist below.
const PERMISSION_OPTIONS: ScreenTimePermissionOptions = {
  features: ["blocking", "monitoring", "shieldOpenApp"],
};

const STEP_TITLE: Partial<Record<PermissionStep["id"], string>> = {
  screenTime: "Screen Time access",
  notifications: "Notifications (shield → open app)",
};

export default function HomeScreen() {
  const { tokens, metadata, refresh } = useNativeSelection(SELECTION_ID);
  const [permissions, setPermissions] = useState<ScreenTimePermissionResponse | null>(null);
  const [isBlocked, setIsBlocked] = useState(false);

  useFocusEffect(
    useCallback(() => {
      refresh();
      // Reflect the real shield state, not just whether a selection exists.
      // Guarded by capability, not platform: where blocking is unavailable
      // (Android today) isBlocked() throws UNSUPPORTED.
      setIsBlocked(ExpoScreenTime.getCapabilities().blocking !== "none" && ExpoScreenTime.isBlocked());
    }, [refresh])
  );

  useEffect(() => {
    ExpoScreenTime.getScreenTimePermissionsAsync(PERMISSION_OPTIONS).then(setPermissions, () => setPermissions(null));
  }, []);

  const appsCount = metadata?.applicationCount ?? 0;
  const categoriesCount = metadata?.categoryCount ?? 0;
  const domainsCount = metadata?.webDomainCount ?? 0;
  const totalSelected = appsCount + categoriesCount + domainsCount;

  const appTokens = tokens.filter((t) => t.type === "application");
  const categoryTokens = tokens.filter((t) => t.type === "category");
  const domainTokens = tokens.filter((t) => t.type === "webDomain");

  const permissionGranted = permissions?.granted ?? false;
  const requestStep = async (step?: PermissionStep["id"]) => {
    const res = await ExpoScreenTime.requestScreenTimePermissionsAsync({
      ...PERMISSION_OPTIONS,
      step,
    });
    setPermissions(res);
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Permission Status */}
      <SectionHeader title="Permission Status" />
      <Card>
        <Row
          icon="shield-checkmark"
          iconColor={permissionGranted ? colors.success : colors.warning}
          title="Screen Time Access"
          subtitle={
            permissionGranted
              ? "Every step granted"
              : `${permissions?.status ?? "…"} · canAskAgain: ${String(permissions?.canAskAgain ?? "…")}`
          }
        />
        {permissions?.steps.map((step) => (
          <View key={step.id}>
            <Separator />
            <Row
              icon={step.status === "granted" ? "checkmark-circle" : "ellipse-outline"}
              iconColor={step.status === "granted" ? colors.success : colors.textTertiary}
              title={STEP_TITLE[step.id] ?? step.id}
              subtitle={step.error ? `${step.status} · ${step.error.code}` : step.status}
              rightElement={
                step.status !== "granted" ? (
                  <ActionButton
                    title="Allow"
                    icon="lock-open"
                    compact
                    onPress={() => requestStep(step.id)}
                  />
                ) : undefined
              }
            />
          </View>
        ))}
        {!permissionGranted && (
          <>
            <Separator />
            <View style={styles.permissionAction}>
              <ActionButton
                title="Request next step"
                icon="arrow-forward-circle"
                onPress={() => requestStep()}
              />
            </View>
          </>
        )}
      </Card>

      {/* Blocking Summary */}
      <SectionHeader title="Blocking Summary" />
      <Card>
        <View style={styles.summaryHeader}>
          <View style={styles.summaryIcon}>
            <Ionicons
              name={isBlocked ? "shield" : "shield-outline"}
              size={32}
              color={isBlocked ? colors.primary : colors.textTertiary}
            />
          </View>
          <View style={styles.summaryContent}>
            <Text style={styles.summaryTitle}>
              {isBlocked ? `${totalSelected} Items Blocked` : "No Blocking Active"}
            </Text>
            <StatusBadge
              status={isBlocked ? "success" : "neutral"}
              label={isBlocked ? "Active" : "Inactive"}
            />
          </View>
        </View>

        {isBlocked && (
          <>
            <Separator />
            <View style={styles.statsRow}>
              <View style={styles.statItem}>
                <Ionicons name="apps" size={20} color={colors.primary} />
                <Text style={styles.statValue}>{appsCount}</Text>
                <Text style={styles.statLabel}>Apps</Text>
              </View>
              <View style={styles.statItem}>
                <Ionicons name="folder" size={20} color={colors.warning} />
                <Text style={styles.statValue}>{categoriesCount}</Text>
                <Text style={styles.statLabel}>Categories</Text>
              </View>
              <View style={styles.statItem}>
                <Ionicons name="globe" size={20} color={colors.success} />
                <Text style={styles.statValue}>{domainsCount}</Text>
                <Text style={styles.statLabel}>Domains</Text>
              </View>
            </View>
          </>
        )}
      </Card>

      {/* Currently Blocked Preview */}
      {isBlocked && (
        <>
          <SectionHeader title="Currently Blocked" />
          <Card>
            {appsCount > 0 && (
              <>
                <Text style={styles.previewLabel}>Apps</Text>
                {appTokens.slice(0, 3).map((token) => (
                  <View key={`app-${token.handle}`} style={styles.blockedItem}>
                    <ApplicationLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="icon-only"
                      style={styles.blockedIcon}
                    />
                    <ApplicationLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="title-only"
                      style={styles.blockedTitle}
                    />
                  </View>
                ))}
                {appsCount > 3 && (
                  <Text style={styles.moreText}>+{appsCount - 3} more apps</Text>
                )}
              </>
            )}

            {categoriesCount > 0 && (
              <>
                {appsCount > 0 && <Separator />}
                <Text style={styles.previewLabel}>Categories</Text>
                {categoryTokens.slice(0, 3).map((token) => (
                  <View key={`cat-${token.handle}`} style={styles.blockedItem}>
                    <CategoryLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="icon-only"
                      style={styles.blockedIcon}
                    />
                    <CategoryLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="title-only"
                      style={styles.blockedTitle}
                    />
                  </View>
                ))}
              </>
            )}

            {domainsCount > 0 && (
              <>
                {(appsCount > 0 || categoriesCount > 0) && <Separator />}
                <Text style={styles.previewLabel}>Web Domains</Text>
                {domainTokens.slice(0, 3).map((token) => (
                  <View key={`dom-${token.handle}`} style={styles.blockedItem}>
                    <WebDomainLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="title-and-icon"
                      style={styles.blockedFull}
                    />
                  </View>
                ))}
              </>
            )}
          </Card>
        </>
      )}

      {/* Empty State */}
      {totalSelected === 0 && permissionGranted && (
        <Card style={styles.emptyCard}>
          <EmptyState
            icon="shield-outline"
            title="No Apps Blocked"
            subtitle="Go to the Blocking tab to select apps to block"
          />
        </Card>
      )}

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
  permissionAction: {
    paddingVertical: 8,
  },
  summaryHeader: {
    flexDirection: "row",
    alignItems: "center",
    padding: 16,
  },
  summaryIcon: {
    width: 56,
    height: 56,
    borderRadius: 12,
    backgroundColor: colors.background,
    justifyContent: "center",
    alignItems: "center",
    marginRight: 16,
  },
  summaryContent: {
    flex: 1,
  },
  summaryTitle: {
    fontSize: 20,
    fontWeight: "600",
    color: colors.text,
    marginBottom: 6,
  },
  statsRow: {
    flexDirection: "row",
    paddingVertical: 16,
    paddingHorizontal: 8,
  },
  statItem: {
    flex: 1,
    alignItems: "center",
  },
  statValue: {
    fontSize: 24,
    fontWeight: "700",
    color: colors.text,
    marginTop: 4,
  },
  statLabel: {
    fontSize: 12,
    color: colors.textTertiary,
    marginTop: 2,
  },
  previewLabel: {
    fontSize: 13,
    fontWeight: "600",
    color: colors.textTertiary,
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 8,
  },
  blockedItem: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  blockedIcon: {
    width: 32,
    height: 32,
    marginRight: 12,
  },
  blockedTitle: {
    flex: 1,
    height: 24,
  },
  blockedFull: {
    flex: 1,
    height: 32,
  },
  moreText: {
    fontSize: 13,
    color: colors.primary,
    paddingHorizontal: 16,
    paddingBottom: 12,
  },
  emptyCard: {
    marginTop: 8,
  },
  footer: {
    height: 20,
  },
});
