import { Ionicons } from "@expo/vector-icons";
import { useFocusEffect } from "expo-router";
import {
  ApplicationLabel,
  CategoryLabel,
  TokenLabel,
  WebDomainLabel,
  type TokenHandle,
} from "expo-screen-time";
import { useCallback } from "react";
import { ScrollView, StyleSheet, Text, View } from "react-native";

import { SELECTION_ID, useNativeSelection } from "../_layout";
import { Card, colors, EmptyState, SectionHeader } from "../components/ui";

function DisplayModeDemo({
  title,
  description,
  token,
}: {
  title: string;
  description: string;
  token: TokenHandle;
}) {
  const LabelComponent =
    token.type === "application"
      ? ApplicationLabel
      : token.type === "category"
        ? CategoryLabel
        : WebDomainLabel;

  return (
    <View style={styles.demoSection}>
      <Text style={styles.demoTitle}>{title}</Text>
      <Text style={styles.demoDescription}>{description}</Text>
      <View style={styles.demoContent}>
        <LabelComponent
          selectionId={SELECTION_ID}
          handle={token.handle}
          displayMode={
            title.includes("Icon")
              ? "icon-only"
              : title.includes("Title")
                ? "title-only"
                : "title-and-icon"
          }
          style={
            title.includes("Icon")
              ? styles.iconOnlyDemo
              : title.includes("Title")
                ? styles.titleOnlyDemo
                : styles.fullDemo
          }
        />
      </View>
    </View>
  );
}

export default function LabelsScreen() {
  const { tokens, refresh } = useNativeSelection(SELECTION_ID);

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh])
  );

  const appTokens = tokens.filter((t) => t.type === "application");
  const categoryTokens = tokens.filter((t) => t.type === "category");
  const domainTokens = tokens.filter((t) => t.type === "webDomain");

  const hasApps = appTokens.length > 0;
  const hasCategories = categoryTokens.length > 0;
  const hasDomains = domainTokens.length > 0;
  const hasAnyToken = hasApps || hasCategories || hasDomains;

  // Get first token of each type for demo
  const appToken = appTokens[0];
  const categoryToken = categoryTokens[0];
  const domainToken = domainTokens[0];

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Introduction */}
      <SectionHeader title="About Token Labels" />
      <Card>
        <View style={styles.infoSection}>
          <Ionicons
            name="information-circle"
            size={24}
            color={colors.primary}
          />
          <Text style={styles.infoText}>
            Token Labels display app icons and names from Screen Time tokens.
            Apple's privacy design means we can only render them visually - we
            cannot extract the actual names programmatically.
          </Text>
        </View>
      </Card>

      {/* Display Mode Explanation */}
      <SectionHeader title="Display Modes" />
      <Card>
        <View style={styles.modeRow}>
          <View style={styles.modeItem}>
            <View
              style={[
                styles.modeIcon,
                { backgroundColor: colors.primary + "20" },
              ]}
            >
              <Ionicons name="image" size={20} color={colors.primary} />
            </View>
            <Text style={styles.modeName}>icon-only</Text>
          </View>
          <View style={styles.modeItem}>
            <View
              style={[
                styles.modeIcon,
                { backgroundColor: colors.warning + "20" },
              ]}
            >
              <Ionicons name="text" size={20} color={colors.warning} />
            </View>
            <Text style={styles.modeName}>title-only</Text>
          </View>
          <View style={styles.modeItem}>
            <View
              style={[
                styles.modeIcon,
                { backgroundColor: colors.success + "20" },
              ]}
            >
              <Ionicons name="apps" size={20} color={colors.success} />
            </View>
            <Text style={styles.modeName}>title-and-icon</Text>
          </View>
        </View>
      </Card>

      {!hasAnyToken && (
        <Card style={styles.emptyCard}>
          <EmptyState
            icon="pricetag-outline"
            title="No Tokens Available"
            subtitle="Block some apps first to see label demos"
          />
        </Card>
      )}

      {/* Application Labels Demo */}
      {hasApps && appToken && (
        <>
          <SectionHeader title="Application Labels" />
          <Card>
            <DisplayModeDemo
              title="Icon Only"
              description='displayMode="icon-only"'
              token={appToken}
            />
            <View style={styles.divider} />
            <DisplayModeDemo
              title="Title Only"
              description='displayMode="title-only"'
              token={appToken}
            />
            <View style={styles.divider} />
            <DisplayModeDemo
              title="Title and Icon (Default)"
              description='displayMode="title-and-icon"'
              token={appToken}
            />
          </Card>

          {/* Side by side demo */}
          <SectionHeader title="Combined Example" />
          <Card>
            <Text style={styles.combinedDescription}>
              Using icon-only + title-only for custom layouts:
            </Text>
            {appTokens.slice(0, 3).map((token) => (
              <View key={`combined-${token.handle}`} style={styles.combinedRow}>
                <ApplicationLabel
                  selectionId={SELECTION_ID}
                  handle={token.handle}
                  displayMode="icon-only"
                  style={styles.combinedIcon}
                />
                <View style={styles.combinedContent}>
                  <ApplicationLabel
                    selectionId={SELECTION_ID}
                    handle={token.handle}
                    displayMode="title-only"
                    style={styles.combinedTitle}
                  />
                  <Text style={styles.combinedSubtitle}>Blocked app</Text>
                </View>
                <Ionicons
                  name="checkmark-circle"
                  size={24}
                  color={colors.success}
                />
              </View>
            ))}
          </Card>
        </>
      )}

      {/* Handle-only demo: no selectionId, the handle resolves on its own */}
      {hasAnyToken && (
        <>
          <SectionHeader title="Handle Only (no selectionId)" />
          <Card>
            <Text style={styles.combinedDescription}>
              A handle is self-describing: these labels receive no selectionId
              and still render, so a handle you kept from an earlier getTokens()
              or onSelectionChange can be labelled on its own.
            </Text>
            {[appToken, categoryToken, domainToken]
              .filter((token): token is TokenHandle => token !== undefined)
              .map((token) => (
                <View key={`handle-only-${token.handle}`} style={styles.combinedRow}>
                  <TokenLabel
                    handle={token.handle}
                    tokenType={token.type}
                    displayMode="title-and-icon"
                    style={styles.fullDemo}
                  />
                </View>
              ))}
          </Card>
        </>
      )}

      {/* Category Labels Demo */}
      {hasCategories && categoryToken && (
        <>
          <SectionHeader title="Category Labels" />
          <Card>
            <DisplayModeDemo
              title="Icon Only"
              description='displayMode="icon-only"'
              token={categoryToken}
            />
            <View style={styles.divider} />
            <DisplayModeDemo
              title="Title Only"
              description='displayMode="title-only"'
              token={categoryToken}
            />
            <View style={styles.divider} />
            <DisplayModeDemo
              title="Title and Icon (Default)"
              description='displayMode="title-and-icon"'
              token={categoryToken}
            />
          </Card>
        </>
      )}

      {/* Web Domain Labels Demo */}
      {hasDomains && domainToken && (
        <>
          <SectionHeader title="Web Domain Labels" />
          <Card>
            <DisplayModeDemo
              title="Icon Only"
              description='displayMode="icon-only"'
              token={domainToken}
            />
            <View style={styles.divider} />
            <DisplayModeDemo
              title="Title Only"
              description='displayMode="title-only"'
              token={domainToken}
            />
            <View style={styles.divider} />
            <DisplayModeDemo
              title="Title and Icon (Default)"
              description='displayMode="title-and-icon"'
              token={domainToken}
            />
          </Card>
        </>
      )}

      {/* Code Example */}
      <SectionHeader title="Usage Example" />
      <Card>
        <View style={styles.codeBlock}>
          <Text style={styles.codeText}>
            {`<ApplicationLabel
  selectionId="blocked_apps"
  handle={token.handle}
  displayMode="icon-only"
  style={{ width: 40, height: 40 }}
/>

<ApplicationLabel
  selectionId="blocked_apps"
  handle={token.handle}
  displayMode="title-only"
  style={{ height: 24 }}
/>

<ApplicationLabel
  selectionId="blocked_apps"
  handle={token.handle}
  displayMode="title-and-icon"
  style={{ height: 44 }}
/>

// selectionId is optional: a handle resolves on its own
<ApplicationLabel
  handle={savedHandle}
  displayMode="title-and-icon"
  style={{ height: 44 }}
/>`}
          </Text>
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
  infoSection: {
    flexDirection: "row",
    padding: 16,
    alignItems: "flex-start",
  },
  infoText: {
    flex: 1,
    fontSize: 14,
    color: colors.textSecondary,
    marginLeft: 12,
    lineHeight: 20,
  },
  modeRow: {
    flexDirection: "row",
    padding: 16,
  },
  modeItem: {
    flex: 1,
    alignItems: "center",
  },
  modeIcon: {
    width: 44,
    height: 44,
    borderRadius: 12,
    justifyContent: "center",
    alignItems: "center",
    marginBottom: 8,
  },
  modeName: {
    fontSize: 11,
    fontWeight: "600",
    color: colors.textTertiary,
  },
  emptyCard: {
    marginTop: 8,
  },
  demoSection: {
    padding: 16,
  },
  demoTitle: {
    fontSize: 15,
    fontWeight: "600",
    color: colors.text,
    marginBottom: 4,
  },
  demoDescription: {
    fontSize: 12,
    fontFamily: "Menlo",
    color: colors.textTertiary,
    marginBottom: 12,
  },
  demoContent: {
    backgroundColor: colors.background,
    borderRadius: 8,
    padding: 12,
    alignItems: "flex-start",
  },
  iconOnlyDemo: {
    width: 48,
    height: 48,
  },
  titleOnlyDemo: {
    height: 28,
    minWidth: 100,
  },
  fullDemo: {
    height: 44,
    minWidth: 150,
  },
  divider: {
    height: 1,
    backgroundColor: colors.separator,
    marginHorizontal: 16,
  },
  combinedDescription: {
    fontSize: 13,
    color: colors.textTertiary,
    padding: 16,
    paddingBottom: 8,
  },
  combinedRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  combinedIcon: {
    width: 44,
    height: 44,
    marginRight: 12,
  },
  combinedContent: {
    flex: 1,
  },
  combinedTitle: {
    height: 22,
  },
  combinedSubtitle: {
    fontSize: 12,
    color: colors.textTertiary,
    marginTop: 2,
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
  footer: {
    height: 20,
  },
});
