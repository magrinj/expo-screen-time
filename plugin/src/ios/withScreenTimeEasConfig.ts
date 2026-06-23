import { ConfigPlugin } from 'expo/config-plugins';

type EasConfigProps = {
  targetName: string;
  bundleIdentifier: string;
  groupIdentifier: string;
};

/**
 * Registers one extension target with EAS Build so the cloud signing step
 * provisions and signs it. Unlike expo-widgets we also declare
 * `com.apple.developer.family-controls` (a restricted capability) — without it
 * the EAS archive fails to sign the Screen Time extensions.
 */
const withScreenTimeEasConfig: ConfigPlugin<EasConfigProps> = (
  config,
  { targetName, bundleIdentifier, groupIdentifier }
) => {
  const entitlements = {
    'com.apple.developer.family-controls': true,
    'com.apple.security.application-groups': [groupIdentifier],
  };

  const existing: any[] =
    config.extra?.eas?.build?.experimental?.ios?.appExtensions ?? [];
  const index = existing.findIndex((ext) => ext.targetName === targetName);

  const appExtensions =
    index === -1
      ? [...existing, { targetName, bundleIdentifier, entitlements }]
      : existing.map((ext, i) =>
          i === index ? { ...ext, bundleIdentifier, entitlements: { ...ext.entitlements, ...entitlements } } : ext
        );

  config.extra = {
    ...config.extra,
    eas: {
      ...config.extra?.eas,
      build: {
        ...config.extra?.eas?.build,
        experimental: {
          ...config.extra?.eas?.build?.experimental,
          ios: {
            ...config.extra?.eas?.build?.experimental?.ios,
            appExtensions,
          },
        },
      },
    },
  };

  return config;
};

export default withScreenTimeEasConfig;
