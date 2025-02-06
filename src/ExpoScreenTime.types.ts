import type { StyleProp, ViewStyle } from 'react-native';

/**
 * @platform ios
 */
export type ActivityToken = {
  data: string;
};

/**
 * @platform ios
 */
export type FamilyActivitySelection = {
  applicationTokens: ActivityToken[];
  categoryTokens: ActivityToken[];
  webDomainTokens: ActivityToken[];
  untokenizedApplicationIdentifiers: string[];
  untokenizedCategoryIdentifiers: string[];
  untokenizedWebDomainIdentifiers: string[];
  includeEntireCategory: boolean;
};

/**
 * @platform ios
 */
export type OnSelectionChangeEventPayload = {
  selection: FamilyActivitySelection;
};

export type ExpoScreenTimeModuleEvents = {};

export type ExpoScreenTimeActivityPickerProps = {
  style?: StyleProp<ViewStyle>;
  headerText?: string;
  footerText?: string;
  selection: FamilyActivitySelection | null;
  onSelectionChange: (event: {
    nativeEvent: OnSelectionChangeEventPayload;
  }) => void;
};
