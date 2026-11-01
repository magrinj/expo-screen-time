# Conventions

API design, commits/releases, dependencies, packaging, docs. The load-bearing ones
— break these and automation or consumers break.

## API design

- **Platform-neutral names.** `blockSelection`, `startMonitoring`, `isBlocked` —
  names must survive the future Android implementation. Before naming anything
  new, check the platform support page; if the
  concept is iOS-structural (tokens, shields, web-domain blocking), tag it
  `@platform ios` in TSDoc.
- **Types live in `src/ExpoScreenTime.types.ts`**, in the existing banner
  sections. Public surface is exported **only** through `src/index.ts`.
- **Opaque `selectionId`** everywhere a set of apps is referenced (see
  [`architecture.md`](./architecture.md)) — never raw tokens across the bridge.
- **Components**: thin `requireNativeView` wrappers; convenience variants as named
  exports (`ApplicationLabel` over `TokenLabel tokenType="application"`); non-iOS
  render a styled fallback, never crash (see `DeviceActivityReport.tsx`).
- **Async when it can fail or leaves the main thread** (`AsyncFunction` +
  `Promise`), sync `Function` for cheap store reads/writes. Errors: `NSError`
  domain `"ExpoScreenTime"`, message includes the offending ID.
- **Swift**: every read-decide-write over block state (App Group records and
  `ManagedSettingsStore`s) inside `BlockStateLock.shared.withLock`, in the app
  via `ScreenTimeManager`; all App Group keys via `SharedDefaultsKeys` with the
  `expo_st_` prefix;
  `// MARK: -` sections mirroring the TS grouping.

## Commits & releases (conventional commits are load-bearing)

- release-please derives **version bumps and CHANGELOG.md from commit messages**.
  PRs are squash-merged and the **PR title becomes the commit** — CI (`PR Title`)
  enforces the conventional format.
- `feat:` → minor · `fix:` → patch · `feat!:`/`BREAKING CHANGE:` → breaking (minor
  while 0.x, `bump-minor-pre-major`) · `docs:`/`chore:`/`ci:`/`refactor:`/`test:`
  → no release.
- Release flow: merge PRs → merge the `chore: release X.Y.Z` PR → tag `vX.Y.Z` +
  GitHub Release + npm publish via **OIDC trusted publishing** (workflow
  `release.yml`, no token). Full detail: [`RELEASING.md`](../RELEASING.md).
- Never reuse a published version — npm burns them permanently (`0.1.0` is
  tombstoned from a 2024 unpublish).

## Dependencies (the SDK-lock policy)

`react`, `react-native`, `@types/react`, `expo`, `expo-module-scripts`,
`@expo/plist` are **pinned to the Expo SDK** (currently SDK 56 / RN 0.85 / React
19.2). Patches are fine; minor/major jumps happen only as part of a deliberate
SDK upgrade. `dependabot.yml` encodes this with `ignore` rules — keep it in sync
when the dep set changes. Sanity check for `@expo/*` bumps: the new version must
still satisfy what `@expo/config-plugins@<SDK>` requires (e.g. `@expo/plist@0.8`
breaks SDK 56's `^0.7.0`).

GitHub Actions bumps from dependabot are generally safe; PRs touching
`.github/workflows/**` can't be merged with a gh token lacking the `workflow`
scope — apply the bump directly on main and close the PR as superseded.

## npm packaging

- `package.json` `files` is an **allowlist** — the only packaging mechanism. When
  adding top-level files/dirs, decide explicitly: ship it or not.
- Never ship: `android/` (stray app project), `example/`, `website/`, `docs/`,
  internal MDs, keystores. Always ship: `build/`, `plugin/build`, `src`,
  `plugin/src`, `ios/`, `targets/`, `app.plugin.js`, `expo-module.config.json`
  (+ auto-included `README`/`LICENSE`).
- Verify with `npm pack --dry-run --ignore-scripts` (Tier 3 of the verification
  loop). `prepack` must keep `EXPO_NONINTERACTIVE=1` or publish hangs in watch
  mode.

## Documentation duty

- **Public API changed ⇒ update `website/docs/` in the same PR.** The Docusaurus
  site is the source of truth; the README is a slim quickstart + links and must
  stay that way (no API reference creep).
- Docs build has `onBrokenLinks: 'throw'` — cross-links use relative `.md` paths;
  headings containing `<>/` get explicit `{#anchor}` ids.
- Repo-internal knowledge (this kind of file) goes in `docs/` at the root —
  **not** in `website/docs/` (user-facing) and not shipped to npm.

## Repo hygiene

- `build/` and `plugin/build` are gitignored but shipped (built by `prepack`).
- `example/ios` + `example/android` are gitignored (prebuild artifacts).
- The root `android/` directory is a stray app project — unused, unshipped;
  delete it when the real Android module work starts.
- Tool caches (`.serena/`…) go in `.gitignore`, never in commits.
