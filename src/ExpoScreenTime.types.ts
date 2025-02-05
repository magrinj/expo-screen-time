import type { StyleProp, ViewStyle } from 'react-native';

export type ApplicationSelection = {
  applicationTokens: string[];
  categoryTokens: string[];
  webDomainTokens: string[];
  untokenizedApplicationIdentifiers: string[];
  untokenizedCategoryIdentifiers: string[];
  untokenizedWebDomainIdentifiers: string[];
  includeEntireCategory: boolean;
}

export type OnSelectionChangeEventPayload = {
  selection: ApplicationSelection;
};

export type ExpoScreenTimeModuleEvents = {
  onChange: (params: ChangeEventPayload) => void;
};

export type ChangeEventPayload = {
  value: string;
};

export type ExpoScreenTimeSelectAppsViewProps = {
  style?: StyleProp<ViewStyle>;
  selection: ApplicationSelection | null;
  onSelectionChange: (event: {
    nativeEvent: OnSelectionChangeEventPayload;
  }) => void;
};
