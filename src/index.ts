// Reexport the native module. On web, it will be resolved to ExpoScreenTimeModule.web.ts
// and on native platforms to ExpoScreenTimeModule.ts
export { default } from './ExpoScreenTimeModule';
export { default as ExpoScreenTimeView } from './ExpoScreenTimeView';
export * from  './ExpoScreenTime.types';
