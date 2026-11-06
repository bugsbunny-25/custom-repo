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
| `PatchEvent.kt` | what a run reports as it goes (`Log`, `StepStarted`, `PatchApplied`, `TaskProgress`) - the `onEvent` callback `PatchApplier` passes in |
| `RunningPatchWatch.kt` | names the patch a run is stuck on once it runs unusually long, since the patcher reports a patch only after it ends |
| `patches/PatchExtensions.kt` | `Patch` compatibility/version helpers the pipeline filters on |
| `PatcherCompatibility.kt` | reads a `.mpp`'s `Patcher-Version` manifest attribute and compares it with the patcher we ship |
| `MorpheComponents.kt` | the morphe-patcher version actually on the classpath |
| `MultiSourceLoader.kt` | loads several `.mpp` bundles in parallel, isolating per-bundle load failures |
| `ThrowableMessages.kt` | cause-chain-aware error messages (`PatchSourceLoadException`) |
| `model/Release.kt`, `model/PatchesBundle.kt` | provider-agnostic release model + the `patches-bundle.json` manifest |
| `network/HttpService.kt` | shared GET / HEAD / streaming download with retry and HTTP 429 handling |
| `patches/*` | `RemotePatchSource` and its GitHub, GitHub pull request and GitLab implementations, the URL-parsing factory, and the per-bundle loader |
| `apk/BundleFormats.kt` | `.apkm`/`.xapk`/`.apks` detection |
| `apk/ApkInspector.kt`, `apk/ApkInspectionResult.kt` | manifest and ABI inspection through ARSCLib; `AndroidArchitectures` lives here too |
| `apk/ApkOutputNaming.kt` | upstream's output-file naming, used only when `Config.outputApk` is null (this server always passes one) |
| `options/PatchOptionsFile.kt`, `options/PatchOptionValues.kt` | the options-file format and the typed option (de)serialization the pipeline resolves patch options through |
| `config/EngineConfig.kt`, `config/EngineConfigRepository.kt` | the engine's own config store - where the pull request source now looks for a GitHub PAT before falling back to the environment |
| `workspace/WorkspaceManager.kt`, `workspace/SessionWorkspace.kt` | the per-run scratch directory the whole pipeline executes inside (`Config.tempDir`, which this server always sets) |
| `PatchedAppStore.kt`, `model/PatchedAppRecord.kt` | upstream's patch-history record, written when `Config.recordHistory` is on |
| `MorpheData.kt`, `CacheManager.kt`, `util/PortablePaths.kt` | the storage-path resolution everything above sits on: `MORPHE_DATA_DIR`, else JAR-adjacent, else `$HOME` |
| `util/Logger.kt` | the engine's own logger - every vendored file logs through it, and `LoggingBridge` routes it into logback |
| `UpdateChecker.kt` | the desktop app's update check, vendored only because `util/Logger.kt` reports its version |
| `util/KeystoreSigner.kt` | signing with the legacy-alias fallback |
| `util/FileChecksum.kt` | SHA-256 + size fingerprint of the patched APK |
| `util/VersionUtils.kt` | `String.normalizeVersion()` and version comparison - where upstream moved `Release.getVersion()` |
| `util/ParallelDelete.kt` | `File.deleteRecursivelyInParallel()`, used to clear the patching temp dir |
| `util/AndroidSdkLocator.kt` | `ANDROID_HOME`/`ANDROID_SDK_ROOT` lookup `config/EngineConfig.kt` resolves `verifyWithSdk` through; this server leaves `Config.sdkToolsPath` null, so no SDK is looked for |

The list grew a lot at v1.20.0. Upstream pulled machinery that used to live in the
desktop app *into* the engine package - APK inspection, the options file, the
logger, the session workspace, the patch-history store, and the data-directory
resolution under all of them - and `PatchEngine.kt` now calls it directly. None
of it is GUI code (no Compose, Koin or Voyager), so vendoring it is cheaper than
patching those calls back out of a pipeline we want to stay a plain `diff`
against upstream. `MorpheData` resolves a writable directory on its own
(`MORPHE_DATA_DIR`, else next to the shadow jar), so nothing here needs a
desktop install to exist.

Three things changed shape at v2.0.0/v2.1.x, all of them visible in
`app.fdroidserver.patching`:

- **`Patch` is no longer generic.** morphe-patcher 2.3.0 dropped the type
  parameter, so every `Patch<*>` became `Patch` - here and in
  `AppVersionSupport.kt`, `PatchApplier.kt`, `PatchLibrary.kt` and
  `PatchSelector.kt`.
- **A run reports events, not progress strings.** `PatchEngine.patch` takes
  `onEvent: (PatchEvent) -> Unit` in place of `onProgress: (String) -> Unit`,
  and writes every `PatchEvent.Log` to `util/Logger.kt` itself before handing
  it over. Since local delta 2 routes that logger into logback, `PatchApplier`
  no longer logs those messages a second time; it logs the step transitions,
  which that stream doesn't carry on its own.
- **The JKS → BKS conversion is gone.** The patcher now reads PKCS12, BKS and
  JKS keystores as they are, so upstream deleted `util/KeystoreImporter.kt`,
  `util/KeystoreConversionUtils.kt` and `util/JksKeyStoreParser.kt` and signs
  with whatever `Config.keystoreDetails` points at. This server's
  `patched-keystore.jks` is read directly now rather than being converted to
  BKS under the engine's data directory first.

`apk/ApkInspector.kt` reads manifests through ARSCLib directly, which is why
`app/build.gradle.kts` depends on it explicitly: morphe-patcher brings it along
at runtime, but a transitive `implementation` dependency isn't on our compile
classpath.

Deliberately **not** vendored: everything still tied to the desktop app's own
environment or UI - `BootstrapDownloader`/`BootstrapProgress`, `PlatformDetector`,
`MorpheConstants`, `AppLinkCommands`/`AdbManager`, `KeystoreService`,
`SignatureIdentity`, `util/DownloadUrlResolver`, `network/HttpClient`, the
patch-browsing layer (`patches/PatchCache`, `PatchRepository`, `PatchResolver`,
`AppVersionResolver`, `SupportedAppCatalog`) and the option/metadata models it
feeds (`options/PatchOption`, `options/PatchPreferencesRepository`,
`model/PatchMetadata`, `model/SupportedApp`, `model/VersionStatus`). This server
gets package metadata straight off the patcher context, resolves its own
download URLs, and keeps its own catalog in SQLite.

## Local deltas from upstream

Kept as small as possible so re-syncing stays cheap. Two, as of v2.1.1:

1. **`PatchEngine.kt` - caller-supplied version filter.** `Config.versionFilter`
   (null by default, which keeps upstream's check) replaces the
   `supportedVersionsFor()` membership test in `isPatchCompatible`. Upstream's
   helper drops `AppTarget(version = null)` ("any version") and ignores
   `isExperimental`, so an experimental "any version" target could never be
   patched. This server passes `supportsAppVersion`
   (`app.fdroidserver.patching.AppVersionSupport`), which honours both and the
   attachment's experimental opt-in.
2. **`util/Logger.kt` - records go through `java.util.logging`.** Upstream
   prints them to stdout/stderr and, once `init()` has run, to a session log
   file under its data directory. This server routes JUL into logback
   (`app.fdroidserver.LoggingBridge`, with `LevelChangePropagator` in
   `logback.xml` pushing `LOG_LEVEL` down onto JUL), so the engine's own
   records - the loudest thing in an unattended patch run - come out in the
   same format and under the same level as everything else. `installJulBridge()`
   becomes a no-op for the same reason: taking `app.morphe`'s JUL handlers over
   would cut morphe-patcher's records out of that pipe and feed this logger
   back into itself.
3. Nothing else. All other files are byte-identical to upstream.

Two deltas were dropped at v1.20.0 because upstream adopted them:

- **Compatibility filtering on `versionName`.** Upstream read
  `packageMetadata.versionCode` and compared it with `supportedVersionsFor()`,
  which returns version *names*; it now reads `versionName` itself, and
  `Result.packageVersion` carries the name (`PatchApplier` reads it straight).
- **GitHub PAT without the desktop GUI config.** The pull request source's PAT
  lookup went through `app.morphe.gui`'s `ConfigRepository` via Koin. Upstream
  moved it into the engine's own `EngineConfigRepository`, behind an injectable
  `patProvider`, keeping the same `GITHUB_TOKEN` / `GH_TOKEN` fallback.

## Re-syncing

**This is automated.** `.github/workflows/morphe-engine-sync.yml` checks
morphe-desktop for a new stable release daily and, when the engine we vendor
actually changed, opens a PR with the files re-copied, the deltas re-applied,
`FORK_INFO` bumped, and `morphe-patcher` moved to whatever upstream now pins.
A release that doesn't touch the engine produces no PR.

The sync refreshes every file already vendored, and handles upstream moving the
package around:

- **New upstream files.** When vendored code references a top-level type or
  public function declared only in a file new upstream, that file is pulled in
  too - repeatedly, so its own new dependencies follow - and the PR calls it
  out. The reference has to be an import, or a mention from inside the same
  package, which is how a desktop-only file avoids being dragged in by a
  passing mention of a common word. New upstream files nothing here uses are
  left behind.
- **Renamed files.** A vendored file with no upstream counterpart at its old
  path, where exactly one upstream file carries that basename, is treated as
  moved: ours is `git mv`d to the new path and refreshed there (v1.20.0 moved
  `PatchExtensions.kt` into `patches/` and `BundleFormats.kt` into `apk/`).
  Anything still missing afterwards was split or deleted upstream, and the PR
  says so rather than papering over it.

To run it yourself - the workflow calls exactly this:

```bash
scripts/sync-morphe-engine.sh            # latest stable upstream release
scripts/sync-morphe-engine.sh v1.16.0    # a specific tag
```

The deltas above live in `local-deltas/` as patch files so the sync can put
them back; see that directory's `README.md` for how to add one and what the
reported statuses mean. Anything other than `clean` needs a human before the
PR is merged.

The `morphe-patcher` and `arsclib` pins move with the engine because the engine
is compiled against them, and a bundle built for a newer patcher than we ship is rejected by
`PatcherCompatibility` at load time. A bump there means re-checking the patcher
API surface used by both the engine and `app.fdroidserver.patching`.
