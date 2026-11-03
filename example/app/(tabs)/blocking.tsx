import { Ionicons } from "@expo/vector-icons";
import { router, useFocusEffect } from "expo-router";
import ExpoScreenTime, {
  ApplicationLabel,
  CategoryLabel,
  isScreenTimeError,
  WebDomainLabel,
  type EnforcementGap,
  type ShieldInteraction,
  type TokenHandle,
} from "expo-screen-time";
import { useCallback, useEffect, useState } from "react";
import { Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";

import { SELECTION_ID, useNativeSelection } from "../_layout";
import {
  ActionButton,
  Card,
  colors,
  EmptyState,
  Row,
  SectionHeader,
  Separator,
} from "../components/ui";

export default function BlockingScreen() {
  const { tokens, metadata, refresh } = useNativeSelection(SELECTION_ID);
  const [remainingUnlock, setRemainingUnlock] = useState(0);
  // The bench for the unlock proofs: any budget (the API floors it at 30 s).
  const [unlockBudget, setUnlockBudget] = useState("300");
  const [interactions, setInteractions] = useState<ShieldInteraction[]>([]);
  // The bench for the gap proofs: every gap, oldest first; `to` is null while one is open.
  const [gaps, setGaps] = useState<EnforcementGap[] | null>(null);

  useFocusEffect(
    useCallback(() => {
      refresh();
      if (ExpoScreenTime.getCapabilities().blocking !== "none") {
        setRemainingUnlock(ExpoScreenTime.getRemainingUnlockSeconds());
      }
    }, [refresh])
  );

  // The budget only moves in ~30s steps while a blocked app is used, so a
  // slow poll is plenty to keep the banner honest.
  useEffect(() => {
    if (remainingUnlock <= 0) return;
    const interval = setInterval(() => {
      setRemainingUnlock(ExpoScreenTime.getRemainingUnlockSeconds());
    }, 5000);
    return () => clearInterval(interval);
  }, [remainingUnlock]);

  const handleTemporaryUnlock = async () => {
    const budget = parseInt(unlockBudget, 10);
    if (!Number.isFinite(budget) || budget <= 0) return;
    try {
      const grant = await ExpoScreenTime.temporaryUnlock(budget);
      setRemainingUnlock(grant.budgetSeconds);
      Alert.alert(
        "Unlocked",
        `${grant.budgetSeconds}s of usage granted, metered in ${grant.meteringSeconds}s steps. The budget is only spent while a blocked app is open.`
      );
    } catch (error) {
      const code = isScreenTimeError(error) ? error.code : "unknown";
      Alert.alert("Error", `Could not unlock (${code}) — block the selection first`);
    }
  };

  // The queue the shield writes: one entry per button press, with the
  // pressed app's handle and the owning activity.
  const handleDrainInteractions = () => {
    setInteractions(ExpoScreenTime.drainShieldInteractions());
  };

  // Periods when a block was intended but not enforced (a user stop, a kill,
  // a reboot, a revoked permission): `to` is null while the gap is open.
  const handleRefreshGaps = async (sinceNow = false) => {
    try {
      setGaps(await ExpoScreenTime.getEnforcementGaps(sinceNow ? Date.now() : undefined));
    } catch (error) {
      const code = isScreenTimeError(error) ? error.code : "unknown";
      Alert.alert("Error", `Could not read the enforcement gaps (${code})`);
    }
  };

  const handleRelock = () => {
    ExpoScreenTime.relock();
    setRemainingUnlock(0);
  };

  // Targeted unlock: lift ONE item of the selection for 3 minutes of its
  // use — the shield snooze, granted from the app. The other items stay
  // blocked; relock() ends it too.
  const handleTargetedUnlock = (target: TokenHandle) => {
    Alert.alert("Unlock this item only?", "3 minutes of its use; everything else stays blocked.", [
      { text: "Cancel", style: "cancel" },
      {
        text: "Unlock",
        onPress: async () => {
          try {
            const grant = await ExpoScreenTime.temporaryUnlock(3 * 60, { target });
            Alert.alert(
              "Unlocked",
              `${grant.budgetSeconds}s of use granted, metered in ${grant.meteringSeconds}s steps.`
            );
          } catch (error) {
            const code = isScreenTimeError(error) ? error.code : "unknown";
            Alert.alert("Error", `Could not unlock this item (${code})`);
          }
        },
      },
    ]);
  };

  const appsCount = metadata?.applicationCount ?? 0;
  const categoriesCount = metadata?.categoryCount ?? 0;
  const domainsCount = metadata?.webDomainCount ?? 0;
  const totalSelected = appsCount + categoriesCount + domainsCount;

  const appTokens = tokens.filter((t) => t.type === "application");
  const catTokens = tokens.filter((t) => t.type === "category");
  const domTokens = tokens.filter((t) => t.type === "webDomain");

  const handleBlock = async () => {
    if (totalSelected === 0) return;

    try {
      ExpoScreenTime.blockSelection(SELECTION_ID);
      await refresh();
      Alert.alert("Success", `${totalSelected} items are now blocked`);
    } catch (error) {
      Alert.alert("Error", "Failed to block selection");
    }
  };

  // Allowlist: shield every app EXCEPT the selected ones. Applications
  // only — the native side rejects categories/domains with INVALID_CONFIG;
  // the button is disabled in that case so the rule is visible up front.
  const handleAllowOnly = async () => {
    if (appsCount === 0) return;

    try {
      ExpoScreenTime.blockSelection(SELECTION_ID, { mode: "allowlist" });
      await refresh();
      Alert.alert(
        "Allowlist applied",
        `Every app except these ${appsCount} is now shielded (your app, Phone and Settings stay open; the web is shielded).`
      );
    } catch (error) {
      const code = isScreenTimeError(error) ? error.code : "unknown";
      Alert.alert("Error", `Failed to apply the allowlist (${code})`);
    }
  };

  const handleUnblock = async () => {
    Alert.alert(
      "Unblock All",
      "Are you sure you want to unblock all items?",
      [
        { text: "Cancel", style: "cancel" },
        {
          text: "Unblock",
          style: "destructive",
          onPress: async () => {
            ExpoScreenTime.unblockAll();
            await refresh();
          },
        },
      ]
    );
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Selection Section */}
      <SectionHeader title="Select Items to Block" />
      <Card>
        <Row
          icon="apps"
          iconColor={colors.primary}
          title="Choose Apps & Categories"
          subtitle={
            totalSelected > 0
              ? `${appsCount} apps, ${categoriesCount} categories, ${domainsCount} domains`
              : "Tap to select items"
          }
          showChevron
          onPress={() => router.push("/select-apps")}
        />
      </Card>

      {/* Current Selection Preview */}
      {totalSelected > 0 && (
        <>
          <SectionHeader title="Current Selection" />
          <Text style={styles.hint}>Long-press an item to unlock it alone for 3 min of use</Text>
          <Card>
            {appsCount > 0 && (
              <>
                <View style={styles.selectionHeader}>
                  <Ionicons name="apps" size={16} color={colors.primary} />
                  <Text style={styles.selectionTitle}>Apps ({appsCount})</Text>
                </View>
                {appTokens.map((token) => (
                  <Pressable
                    key={`sel-app-${token.handle}`}
                    style={styles.selectionItem}
                    onLongPress={() => handleTargetedUnlock(token)}
                  >
                    <ApplicationLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="icon-only"
                      style={styles.selectionIcon}
                    />
                    <ApplicationLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="title-only"
                      style={styles.selectionName}
                    />
                  </Pressable>
                ))}
              </>
            )}

            {categoriesCount > 0 && (
              <>
                {appsCount > 0 && <Separator />}
                <View style={styles.selectionHeader}>
                  <Ionicons name="folder" size={16} color={colors.warning} />
                  <Text style={styles.selectionTitle}>Categories ({categoriesCount})</Text>
                </View>
                {catTokens.map((token) => (
                  <Pressable
                    key={`sel-cat-${token.handle}`}
                    style={styles.selectionItem}
                    onLongPress={() => handleTargetedUnlock(token)}
                  >
                    <CategoryLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="icon-only"
                      style={styles.selectionIcon}
                    />
                    <CategoryLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="title-only"
                      style={styles.selectionName}
                    />
                  </Pressable>
                ))}
              </>
            )}

            {domainsCount > 0 && (
              <>
                {(appsCount > 0 || categoriesCount > 0) && <Separator />}
                <View style={styles.selectionHeader}>
                  <Ionicons name="globe" size={16} color={colors.success} />
                  <Text style={styles.selectionTitle}>Web Domains ({domainsCount})</Text>
                </View>
                {domTokens.map((token) => (
                  <Pressable
                    key={`sel-dom-${token.handle}`}
                    style={styles.selectionItem}
                    onLongPress={() => handleTargetedUnlock(token)}
                  >
                    <WebDomainLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="title-and-icon"
                      style={styles.selectionFull}
                    />
                  </Pressable>
                ))}
              </>
            )}
          </Card>

          {/* Block Action */}
          <ActionButton
            title="Block Selected Items"
            icon="shield"
            color={colors.primary}
            onPress={handleBlock}
          />

          {/* Allowlist Action — apps only */}
          <ActionButton
            title={
              categoriesCount + domainsCount > 0
                ? "Allow Only These (apps only)"
                : "Allow Only These Apps"
            }
            icon="shield-checkmark"
            color={colors.success}
            disabled={appsCount === 0 || categoriesCount + domainsCount > 0}
            onPress={handleAllowOnly}
          />

          {/* Unblock Action */}
          <ActionButton
            title="Unblock All"
            icon="shield-outline"
            color={colors.danger}
            onPress={handleUnblock}
          />

          {/* Temporary Unlock (earned time) */}
          <SectionHeader title="Temporary Unlock" />
          {remainingUnlock > 0 ? (
            <>
              <Card>
                <Row
                  icon="hourglass"
                  iconColor={colors.warning}
                  title={`${remainingUnlock}s (~${Math.ceil(remainingUnlock / 60)} min) of usage left`}
                  subtitle="Spent only while a blocked app is open"
                />
              </Card>
              <ActionButton
                title="Relock Now"
                icon="lock-closed"
                color={colors.danger}
                onPress={handleRelock}
              />
            </>
          ) : (
            <>
              <Card>
                <View style={styles.budgetRow}>
                  <Text style={styles.budgetLabel}>Budget (seconds of use)</Text>
                  <TextInput
                    style={styles.budgetInput}
                    value={unlockBudget}
                    onChangeText={(text) => setUnlockBudget(text.replace(/\D/g, ""))}
                    keyboardType="number-pad"
                    selectTextOnFocus
                    placeholder="300"
                    accessibilityLabel="Unlock budget"
                  />
                </View>
              </Card>
              <ActionButton
                title="Unlock for this budget of usage"
                icon="hourglass-outline"
                color={colors.warning}
                onPress={handleTemporaryUnlock}
              />
            </>
          )}

          {/* Shield interactions */}
          <SectionHeader title="Shield Interactions" />
          <ActionButton
            title="Drain shield interactions"
            icon="list"
            color={colors.primary}
            onPress={handleDrainInteractions}
          />
          {interactions.length > 0 && (
            <Card>
              {interactions.map((press, index) => (
                <Row
                  key={`press-${press.timestamp}-${index}`}
                  icon={press.button === "primary" ? "radio-button-on" : "radio-button-off"}
                  iconColor={colors.warning}
                  title={`${press.button} · ${press.target?.type ?? "no target"} · ${press.activityName ?? "manual block"}`}
                  subtitle={new Date(press.timestamp).toLocaleTimeString()}
                />
              ))}
            </Card>
          )}

          {/* Enforcement gaps */}
          <SectionHeader title="Enforcement Gaps" />
          <ActionButton
            title="Refresh enforcement gaps"
            icon="alert-circle-outline"
            color={colors.primary}
            onPress={() => handleRefreshGaps()}
          />
          <ActionButton
            title="Gaps still open (since now)"
            icon="time-outline"
            color={colors.primary}
            onPress={() => handleRefreshGaps(true)}
          />
          {gaps !== null && (
            <Card>
              {gaps.length === 0 && <Text style={styles.hint}>No gap recorded</Text>}
              {gaps.map((gap, index) => (
                <Row
                  key={`gap-${gap.from}-${index}`}
                  icon={gap.to === null ? "alert-circle" : "alert-circle-outline"}
                  iconColor={gap.to === null ? colors.danger : colors.warning}
                  title={`${gap.reason} · ${gap.to === null ? "open" : `${Math.round((gap.to - gap.from) / 1000)} s`}`}
                  subtitle={`${new Date(gap.from).toLocaleTimeString()} → ${gap.to === null ? "…" : new Date(gap.to).toLocaleTimeString()}`}
                />
              ))}
            </Card>
          )}
        </>
      )}

      {totalSelected === 0 && (
        <Card style={styles.emptyCard}>
          <EmptyState
            icon="hand-left-outline"
            title="No Items Selected"
            subtitle="Tap 'Choose Apps & Categories' to select items to block"
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
  budgetRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  budgetLabel: {
    fontSize: 15,
    color: colors.text,
  },
  budgetInput: {
    minWidth: 80,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 6,
    fontSize: 16,
    textAlign: "right",
    color: colors.text,
  },
  hint: {
    fontSize: 12,
    color: colors.textTertiary,
    paddingHorizontal: 16,
    paddingBottom: 8,
  },
  selectionHeader: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 8,
  },
  selectionTitle: {
    fontSize: 13,
    fontWeight: "600",
    color: colors.textTertiary,
    marginLeft: 8,
  },
  selectionItem: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  selectionIcon: {
    width: 32,
    height: 32,
    marginRight: 12,
  },
  selectionName: {
    flex: 1,
    height: 24,
  },
  selectionFull: {
    flex: 1,
    height: 32,
  },
  emptyCard: {
    marginTop: 8,
  },
  footer: {
    height: 20,
  },
});
