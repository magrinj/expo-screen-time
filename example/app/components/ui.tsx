import { Ionicons } from "@expo/vector-icons";
import React from "react";
import {
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  ViewStyle,
} from "react-native";

// Theme colors
export const colors = {
  background: "#F2F2F7",
  cardBackground: "#FFFFFF",
  primary: "#007AFF",
  success: "#34C759",
  warning: "#FF9500",
  danger: "#FF3B30",
  text: "#000000",
  textSecondary: "#3C3C43",
  textTertiary: "#8E8E93",
  border: "#C6C6C8",
  separator: "#E5E5EA",
};

// Card component
export function Card({
  children,
  style,
}: {
  children: React.ReactNode;
  style?: ViewStyle;
}) {
  return <View style={[styles.card, style]}>{children}</View>;
}

// Section header
export function SectionHeader({
  title,
  rightElement,
}: {
  title: string;
  rightElement?: React.ReactNode;
}) {
  return (
    <View style={styles.sectionHeaderContainer}>
      <Text style={styles.sectionHeader}>{title}</Text>
      {rightElement}
    </View>
  );
}

// Row item for lists
export function Row({
  icon,
  iconColor,
  title,
  subtitle,
  value,
  onPress,
  showChevron = false,
  rightElement,
}: {
  icon?: keyof typeof Ionicons.glyphMap;
  iconColor?: string;
  title: string;
  subtitle?: string;
  value?: string;
  onPress?: () => void;
  showChevron?: boolean;
  rightElement?: React.ReactNode;
}) {
  const content = (
    <View style={styles.row}>
      {icon && (
        <View
          style={[
            styles.iconContainer,
            { backgroundColor: iconColor || colors.primary },
          ]}
        >
          <Ionicons name={icon} size={18} color="#FFFFFF" />
        </View>
      )}
      <View style={styles.rowContent}>
        <Text style={styles.rowTitle}>{title}</Text>
        {subtitle && <Text style={styles.rowSubtitle}>{subtitle}</Text>}
      </View>
      {value && <Text style={styles.rowValue}>{value}</Text>}
      {rightElement}
      {showChevron && (
        <Ionicons name="chevron-forward" size={20} color={colors.textTertiary} />
      )}
    </View>
  );

  if (onPress) {
    return (
      <TouchableOpacity onPress={onPress} activeOpacity={0.7}>
        {content}
      </TouchableOpacity>
    );
  }

  return content;
}

// Action button
export function ActionButton({
  title,
  icon,
  color = colors.primary,
  onPress,
  disabled = false,
  compact = false,
}: {
  title: string;
  icon?: keyof typeof Ionicons.glyphMap;
  color?: string;
  onPress: () => void;
  disabled?: boolean;
  compact?: boolean;
}) {
  return (
    <TouchableOpacity
      style={[
        compact ? styles.actionButtonCompact : styles.actionButton,
        { backgroundColor: disabled ? colors.textTertiary : color },
      ]}
      onPress={onPress}
      disabled={disabled}
      activeOpacity={0.8}
    >
      {icon && <Ionicons name={icon} size={20} color="#FFFFFF" style={{ marginRight: 8 }} />}
      <Text style={styles.actionButtonText}>{title}</Text>
    </TouchableOpacity>
  );
}

// Status badge
export function StatusBadge({
  status,
  label,
}: {
  status: "success" | "warning" | "danger" | "neutral";
  label: string;
}) {
  const statusColors = {
    success: colors.success,
    warning: colors.warning,
    danger: colors.danger,
    neutral: colors.textTertiary,
  };

  return (
    <View style={[styles.badge, { backgroundColor: statusColors[status] + "20" }]}>
      <View style={[styles.badgeDot, { backgroundColor: statusColors[status] }]} />
      <Text style={[styles.badgeText, { color: statusColors[status] }]}>{label}</Text>
    </View>
  );
}

// Empty state
export function EmptyState({
  icon,
  title,
  subtitle,
}: {
  icon: keyof typeof Ionicons.glyphMap;
  title: string;
  subtitle?: string;
}) {
  return (
    <View style={styles.emptyState}>
      <Ionicons name={icon} size={48} color={colors.textTertiary} />
      <Text style={styles.emptyStateTitle}>{title}</Text>
      {subtitle && <Text style={styles.emptyStateSubtitle}>{subtitle}</Text>}
    </View>
  );
}

// Separator
export function Separator() {
  return <View style={styles.separator} />;
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.cardBackground,
    borderRadius: 12,
    marginHorizontal: 16,
    marginVertical: 8,
    overflow: "hidden",
  },
  sectionHeaderContainer: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginHorizontal: 16,
    marginTop: 24,
    marginBottom: 8,
  },
  sectionHeader: {
    fontSize: 13,
    fontWeight: "600",
    color: colors.textTertiary,
    textTransform: "uppercase",
    letterSpacing: 0.5,
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 12,
    paddingHorizontal: 16,
    minHeight: 44,
  },
  iconContainer: {
    width: 30,
    height: 30,
    borderRadius: 6,
    justifyContent: "center",
    alignItems: "center",
    marginRight: 12,
  },
  rowContent: {
    flex: 1,
  },
  rowTitle: {
    fontSize: 17,
    color: colors.text,
  },
  rowSubtitle: {
    fontSize: 13,
    color: colors.textTertiary,
    marginTop: 2,
  },
  rowValue: {
    fontSize: 17,
    color: colors.textTertiary,
    marginRight: 4,
  },
  actionButton: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 14,
    paddingHorizontal: 20,
    borderRadius: 12,
    marginHorizontal: 16,
    marginVertical: 8,
  },
  actionButtonCompact: {
    flex: 1,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 10,
    marginHorizontal: 8,
    marginVertical: 8,
  },
  actionButtonText: {
    fontSize: 17,
    fontWeight: "600",
    color: "#FFFFFF",
  },
  badge: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 4,
    paddingHorizontal: 10,
    borderRadius: 12,
  },
  badgeDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 6,
  },
  badgeText: {
    fontSize: 13,
    fontWeight: "600",
  },
  emptyState: {
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 40,
    paddingHorizontal: 20,
  },
  emptyStateTitle: {
    fontSize: 17,
    fontWeight: "600",
    color: colors.text,
    marginTop: 12,
    textAlign: "center",
  },
  emptyStateSubtitle: {
    fontSize: 15,
    color: colors.textTertiary,
    marginTop: 4,
    textAlign: "center",
  },
  separator: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: colors.separator,
    marginLeft: 58,
  },
});
