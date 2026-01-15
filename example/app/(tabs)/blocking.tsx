import { Ionicons } from "@expo/vector-icons";
import { router, useFocusEffect } from "expo-router";
import ExpoScreenTime, {
  ApplicationLabel,
  CategoryLabel,
  WebDomainLabel,
} from "expo-screen-time";
import { useCallback } from "react";
import { Alert, ScrollView, StyleSheet, Text, View } from "react-native";

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

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh])
  );

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
          <Card>
            {appsCount > 0 && (
              <>
                <View style={styles.selectionHeader}>
                  <Ionicons name="apps" size={16} color={colors.primary} />
                  <Text style={styles.selectionTitle}>Apps ({appsCount})</Text>
                </View>
                {appTokens.map((token) => (
                  <View key={`sel-app-${token.handle}`} style={styles.selectionItem}>
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
                  </View>
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
                  <View key={`sel-cat-${token.handle}`} style={styles.selectionItem}>
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
                  </View>
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
                  <View key={`sel-dom-${token.handle}`} style={styles.selectionItem}>
                    <WebDomainLabel
                      selectionId={SELECTION_ID}
                      handle={token.handle}
                      displayMode="title-and-icon"
                      style={styles.selectionFull}
                    />
                  </View>
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

          {/* Unblock Action */}
          <ActionButton
            title="Unblock All"
            icon="shield-outline"
            color={colors.danger}
            onPress={handleUnblock}
          />
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
