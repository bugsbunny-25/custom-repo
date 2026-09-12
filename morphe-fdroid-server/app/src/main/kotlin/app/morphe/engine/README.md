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
| `patches/*` | `RemotePatchSource` and its GitHub and GitLab implementations, the URL-parsing factory, and the per-bundle loader |
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
2. Nothing else. All other files are byte-identical to upstream.

## Re-syncing

```bash
git clone --depth 1 https://github.com/MorpheApp/morphe-desktop /tmp/morphe-desktop
diff -ru /tmp/morphe-desktop/src/main/kotlin/app/morphe/engine \
         morphe-fdroid-server/app/src/main/kotlin/app/morphe/engine
```

Re-apply the deltas above, and check `gradle/libs.versions.toml` against
morphe-desktop's own catalog - the engine is written against a specific
`morphe-patcher`, and a bundle built for a newer patcher than we ship is
rejected by `PatcherCompatibility` at load time.
