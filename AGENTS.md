# AGENTS.md

Guidance for AI agents (Claude Code, Codex, etc.) working in this repository.
`CLAUDE.md` imports this file — keep everything agent-facing here, in one place.

## Project overview

Expo module for app blocking, schedule monitoring, custom shields and usage
reports, on iOS and Android behind one API. iOS wraps Apple's Screen Time APIs
(FamilyControls, ManagedSettings, DeviceActivity); Android has no such API, so
the module ships its own enforcement engine (Kotlin, `android/`).

- **Both platforms ship.** iOS is OS-enforced and implements every API but
  `openApplication` (iOS cannot launch another app). Android is `friction`: a
  foreground service draws the shield, the user can stop it, and
  `getEnforcementGaps()` reports such periods. Categories, web domains and the
  app-removal / installation restrictions are iOS-only, and `openApplication`
  is off on Android too (the capability is off; the functions fail with
  `UNSUPPORTED`).
  `website/docs/platform-support.md` is the user-facing status; the per-API
  plan and its decisions live in
  the platform support page. Read it before
  Android work.
- Ships a **first-party config plugin** (`plugin/`) that generates 4 iOS
  app-extension targets directly in the consumer's Xcode project (no
  `@bacons/apple-targets`).
- Published to npm as `expo-screen-time`; releases are fully automated
  (release-please + OIDC) — see [`RELEASING.md`](./RELEASING.md).

## Commands

```bash
bun install            # bun is the package manager (packageManager: bun@1.3.14)
bun run build          # module (build/) + config plugin (plugin/build/) — tsc
bun run lint           # oxlint (Expo shared config, oxlint.config.mjs)
bun run test           # jest (src + plugin), then the reference apps type-checked against src/
cd example && bun install && bun run ios   # example app (postinstall builds + links)
cd example && bun run android              # the same app on Android
cd example && bun expo prebuild --platform android   # example/android is a gitignored prebuild artifact
cd example/android && ./gradlew :expo-screen-time:testDebugUnitTest   # Kotlin unit tests — CI runs no Gradle, run them locally
cd website && bun install && bun start     # Docusaurus docs site
```

## Repo map

| Path | What it is |
|---|---|
| `src/` | TypeScript API layer (module declaration, types, native-view wrappers, hooks) |
| `ios/` | Native module (Swift): module definition, ScreenTimeManager, SelectionStore, views |
| `targets/<Name>/` | Swift sources + Info.plist for the 4 app extensions, copied by the plugin at prebuild |
| `plugin/src/` | Expo config plugin (entitlements + Xcode target generation + EAS config) |
| `example/` | expo-router example app exercising every feature |
| `website/` | Docusaurus **user docs** (deployed to GitHub Pages) |
| `docs/` | **Agent/contributor rules** (this repo's conventions — see below) |
| `android/` | Android module (Kotlin, `src/main/java/expo/modules/screentime/`): module definition + `NativeFunctions.kt` registry, selection store, permission steps, the enforcement engine (`ScreenTimeEngineService` in the `:screentime` process, restart chain, watchdog), shield Activity, monitoring, unlock, enforcement gaps, report renderer; `src/main/res/` its drawables/strings; `src/test/` the JUnit tests; `src/main/AndroidManifest.xml` the merged permissions and components |

## Rules — read before touching the matching area

The distilled conventions of this codebase, derived from how it's actually built.
**Read the relevant doc before working in that area:**

- [`docs/architecture.md`](./docs/architecture.md) — the three layers, the opaque
  `selectionId` principle, the App Group bridge, how extensions communicate.
- [`docs/development.md`](./docs/development.md) — the verification loop every
  change must pass (build/lint/test + prebuild regression check), how to test.
- [`docs/config-plugin.md`](./docs/config-plugin.md) — the hard-won pbxproj rules:
  expo-widgets alignment, idempotency, the basename-dedup and `undefined/` group
  gotchas, `--clean` semantics. **Do not modify `plugin/src/ios/` without this.**
- [`docs/conventions.md`](./docs/conventions.md) — API design, `@platform` tagging,
  conventional commits (they drive releases), dependabot SDK-lock policy, npm
  tarball hygiene, docs-update duty.

Non-negotiables (the short list):

1. **Never break plugin idempotency** — re-running prebuild with `--no-clean` must
   change nothing (`bun run test` guards this; extend the test when wiring changes).
2. **When in doubt in `plugin/src/ios/`, match expo-widgets** (`../expo-widgets`,
   Expo's first-party reference). Diverging from it requires a stated reason.
3. **Tokens are opaque, and JS must treat them that way** — JS addresses
   selections via `selectionId` strings and tokens via `TokenHandle`s. Handles
   (and `exportSelection` blobs) are derived from encoded tokens — undecodable
   even natively, but never parse, construct, or interpret them in JS, and never
   add APIs that require JS to understand token contents.
4. **Conventional commits are load-bearing** — release-please derives versions and
   the changelog from them. PR titles must be conventional (CI enforces it).
5. **Public API changed? Update `website/docs/`** in the same PR. README stays a
   slim quickstart; the site is the source of truth.
6. **iOS-only APIs get `@platform ios`** in TSDoc; check
   the platform support page before naming anything new (names should survive
   the Android implementation).


## Key references

- [`RELEASING.md`](./RELEASING.md) — release pipeline (release-please → OIDC npm publish)
- the platform support page — iOS ↔ Android API plan
- [`CONTRIBUTING.md`](./CONTRIBUTING.md) — human contributor guide (setup, PR rules)

Known open item: runtime behavior of the permission-rejection and shield-state
fixes is still unverified on a **physical device** (FamilyControls doesn't run on
the simulator).
