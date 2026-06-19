// The example consumes the in-repo expo-screen-time library via a node_modules
// symlink to the repo root (created by the package.json "postinstall" script).
// Metro's symlink support resolves it to ../build and watches the real source;
// we watch the repo root and dedupe the library's react/react-native against the
// example's copy to avoid duplicate-React errors.
const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const root = path.resolve(__dirname, '..');
const config = getDefaultConfig(__dirname);

config.watchFolders = [root];
config.resolver.nodeModulesPaths = [
  path.resolve(__dirname, 'node_modules'),
  path.resolve(root, 'node_modules'),
];
// Dedupe the library's react/react-native (resolved from root) against the example's.
config.resolver.blockList = [
  ...Array.from(config.resolver.blockList ?? []),
  new RegExp(path.resolve(root, 'node_modules', 'react') + '/.*'),
  new RegExp(path.resolve(root, 'node_modules', 'react-native') + '/.*'),
];

module.exports = config;
