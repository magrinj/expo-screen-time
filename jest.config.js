// Tests under src/ are pure React/TS (the report-template serializer) — no
// react-native runtime needed, so reuse the lightweight node-env plugin preset
// (babel-preset-expo transform) instead of the full jest-expo multi-project one.
// The plugin's own tests keep their explicit config via `expo-module test plugin`.
module.exports = {
  ...require("expo-module-scripts/jest-preset-plugin.js"),
  roots: ["<rootDir>/src"],
};
