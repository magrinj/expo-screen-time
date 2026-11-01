import base from 'expo-module-scripts/oxlint.config.base';
import { defineConfig } from 'oxlint';

// Expo's shared config (oxlint-config-universe/native). oxlint does not
// inherit `ignorePatterns` through `extends`, so they are re-applied here.
export default defineConfig({
  extends: [base],
  ignorePatterns: [...base.ignorePatterns, 'example/**', 'website/**', 'android/**'],
});
