# Vendored Morphe engine

These files are a **hard fork of the `app.morphe.engine` package from
[MorpheApp/morphe-desktop](https://github.com/MorpheApp/morphe-desktop)** - the
official Morphe desktop app - taken at the commit recorded in `FORK_INFO`.
They are GPL-3.0 licensed; see `NOTICE` in this directory for the upstream
notice that GPLv3 §7b requires us to carry, and the file headers for the
upstream copyright lines.

## Why vendored rather than depended on

morphe-desktop publishes no library artifact - it's a Compose Desktop
application, and its engine package is compiled into the app jar together with
the GUI, Koin, Voyager and Compose. There is nothing on GitHub Packages or
Maven Central to depend on, so the only way to run the *same* patching code the
desktop app runs is to carry the source.

The package name (`app.morphe.engine`) and the internal layout are kept
**identical to upstream** on purpose: re-syncing is then a plain `diff` against
a fresh checkout of morphe-desktop, and the local deltas listed below are the
only thing to re-apply.

## What is vendored

Only the headless parts - everything a server needs to load patch bundles from
remote sources and patch an APK:

| File | Role |
| --- | --- |
| `PatchEngine.kt` | the patching pipeline: merge bundle → filter patches → apply → rebuild → sign |
| `PatchExtensions.kt` | `Patch<*>` compatibility/version helpers the pipeline filters on |
| `PatcherCompatibility.kt` | reads a `.mpp`'s `Patcher-Version` manifest attribute and compares it with the patcher we ship |
| `MorpheComponents.kt` | the morphe-patcher version actually on the classpath |
| `MultiSourceLoader.kt` | loads several `.mpp` bundles in parallel, isolating per-bundle load failures |
| `ThrowableMessages.kt` | cause-chain-aware error messages (`PatchSourceLoadException`) |
| `model/Release.kt`, `model/PatchesBundle.kt` | provider-agnostic release model + the `patches-bundle.json` manifest |
| `network/HttpService.kt` | shared GET / HEAD / streaming download with retry and HTTP 429 handling |
| `patches/*` | `RemotePatchSource` and its GitHub, GitHub pull request and GitLab implementations, the URL-parsing factory, and the per-bundle loader |
| `util/BundleFormats.kt` | `.apkm`/`.xapk`/`.apks` detection |
| `util/KeystoreSigner.kt` | signing with the legacy-alias fallback |

Deliberately **not** vendored: everything tied to the desktop app's own
environment - `MorpheData`/`CacheManager`/`PatchCache` (per-user data dirs),
`BootstrapDownloader`, `UpdateChecker`, `PatchedAppStore`, the keystore
import/JKS conversion helpers, `PlatformDetector`, `AppLinkCommands`, and
`ApkManifestReader`/`ApkOutputNaming`/`FileChecksum` (this server gets package
metadata straight off the patcher context and names its own output files).

## Local deltas from upstream

Kept as small as possible so re-syncing stays cheap:

1. **`PatchEngine.kt` - compatibility filtering matches on `versionName`, not
   `versionCode`.** Upstream reads `packageMetadata.versionCode` and compares
   it against `Patch.supportedVersionsFor()`, which returns `AppTarget.version`
   - *version names* (`"20.13.41"`), not version codes (`"1546188352"`). The
   mismatch never fires upstream because the only caller of this filter
   (`gui/util/PatchService.kt`) passes `forceCompatibility = true`, and the CLI
   runs its own pipeline that reads `versionName` (`PatchCommand.kt:630`). We
   patch through this filter unattended, so it has to be right. `Result` now
   carries both `packageVersionName` and `packageVersionCode`.
2. **`patches/PullRequestPatchSource.kt` - GitHub PAT comes from the
   environment only.** Upstream reads it from the desktop app's
   `ConfigRepository` (`app.morphe.gui`), which isn't vendored, then falls back
   to `GITHUB_TOKEN` / `GH_TOKEN`. We keep just the env fallback.
3. Nothing else. All other files are byte-identical to upstream.

## Re-syncing

**This is automated.** `.github/workflows/morphe-engine-sync.yml` checks
morphe-desktop for a new stable release daily and, when the engine we vendor
actually changed, opens a PR with the files re-copied, the deltas re-applied,
`FORK_INFO` bumped, and `morphe-patcher` moved to whatever upstream now pins.
A release that doesn't touch the engine produces no PR.

The sync only refreshes files already vendored, with one exception: when a
vendored file references a type declared in a file new upstream, that file is
pulled in too (repeatedly, so its own new dependencies follow), and the PR
calls it out. New upstream files nothing here uses are left behind.

To run it yourself - the workflow calls exactly this:

```bash
scripts/sync-morphe-engine.sh            # latest stable upstream release
scripts/sync-morphe-engine.sh v1.16.0    # a specific tag
```

The deltas above live in `local-deltas/` as patch files so the sync can put
them back; see that directory's `README.md` for how to add one and what the
reported statuses mean. Anything other than `clean` needs a human before the
PR is merged.

The `morphe-patcher` pin moves with the engine because the engine is compiled
against it, and a bundle built for a newer patcher than we ship is rejected by
`PatcherCompatibility` at load time. A bump there means re-checking the patcher
API surface used by both the engine and `app.fdroidserver.patching`.
