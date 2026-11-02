# Releasing

Releases are automated with [release-please](https://github.com/googleapis/release-please)
and published to npm via **OIDC trusted publishing** (no stored token). You never
bump the version, write the changelog, or create a tag by hand — conventional
commits drive all of it.

## Pipeline at a glance

| Workflow | Trigger | Does |
|---|---|---|
| `ci.yml` | every PR + push to `main` | lint · build · test · prebuild check |
| `pr-title.yml` | every PR | enforces a Conventional Commit PR title |
| `release.yml` | push to `main` | maintains the Release PR; on its merge → tag + GitHub Release + **npm publish (OIDC)** |
| `docs.yml` | `website/**` changes | builds + deploys the docs site to GitHub Pages |

## One-time setup

1. **Push this setup to `main`.**
2. **Anchor the baseline** so release-please changelogs from here, not all history:
   ```bash
   git tag v0.1.1 && git push origin v0.1.1
   ```
   (matches the manually-published `0.1.1` and `.release-please-manifest.json`.)
3. **Configure the npm Trusted Publisher** — npmjs.com → package → Settings →
   Trusted Publisher → GitHub Actions: repo `magrinj/expo-screen-time`, workflow
   file **`release.yml`**.
4. **GitHub settings:**
   - Settings → General → Pull Requests: enable **squash merging**, set the
     commit message to **"Pull request title and description"** (so the PR title
     becomes the commit). Disable merge commits + rebase.
   - Settings → Branches → protect `main`: require a PR, and require status checks
     **CI / check**, **CI / prebuild**, **PR Title / conventional-title**,
     **Docs / build** (they appear in the list only after each has run once).

## Normal release flow

1. Open PRs with **Conventional Commit titles** and squash-merge them to `main`:
   - `feat:` → minor bump · `fix:` → patch · `feat!:` / `BREAKING CHANGE:` →
     breaking (minor while `0.x`) · `docs:`/`chore:`/`ci:`/`refactor:`/`test:` →
     no release.
2. release-please opens/updates a **`chore: release X.Y.Z` PR** that bumps
   `package.json` + `.release-please-manifest.json` and writes `CHANGELOG.md`.
   It accumulates every mergeable change until you release.
3. **Merge the Release PR** when you're ready to ship.
4. `release.yml` then automatically: creates the `vX.Y.Z` tag + GitHub Release,
   builds, and runs `npm publish` via OIDC (provenance attached automatically).
5. Verify: `npm view expo-screen-time version`.

That's the whole loop — **merge PRs → merge the Release PR → it ships.**

## Manual / emergency publish

If you ever need to publish outside the pipeline (local machine):

```bash
# bump to a NEVER-PREVIOUSLY-PUBLISHED version first (npm burns used versions —
# e.g. 0.1.0 is permanently taken from the earlier unpublish), then:
npm whoami            # or: npm login
npm publish --access public
```

Prefer the automated flow; this is only a fallback.

## The npm publish failed after the release was created

The tag and the GitHub Release exist, but npm does not have the version (for
example `E404` on `PUT`, which means the Trusted Publisher is missing or does
not name `release.yml`). Re-running the failed job does **not** publish:
release-please already labelled the Release PR `autorelease: tagged`, so it
reports no new release and the publish steps are skipped. Fix the cause, then
publish the existing tag by hand:

```bash
gh workflow run release.yml -f tag=v0.2.0
```

The run checks out the tag, checks that it matches `package.json`, and
publishes through the same OIDC trusted publisher.

## Notes

- **Burned versions:** `0.1.0` was published+unpublished in 2024 and can never be
  reused. Always go forward.
- **First Release PR:** the first run after bootstrap proposes the next version
  (e.g. `0.1.2` / `0.2.0`) from commits since `v0.1.1`.
- **OIDC only:** the publish step in `release.yml` fails until the Trusted
  Publisher (setup step 3) is configured.
