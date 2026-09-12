# Local deltas

Every intentional difference between the vendored engine and upstream
morphe-desktop, as a patch file. `scripts/sync-morphe-engine.sh` re-applies
each one after copying fresh upstream files, so a re-sync doesn't silently
revert our fixes.

Paths inside the patches are relative to the engine directory (`a/PatchEngine.kt`,
not `a/morphe-fdroid-server/.../PatchEngine.kt`) — the script supplies the rest
with `git apply --directory=`.

## Current deltas

| File | What it changes | Why |
| --- | --- | --- |
| `0001-filter-on-versionName.patch` | `PatchEngine.kt` filters compatibility on `packageMetadata.versionName` instead of `versionCode`, and `Result` carries both | Upstream compares `versionCode` against `Patch.supportedVersionsFor()`, which returns version *names*. It never bites upstream because its only caller passes `forceCompatibility = true` and the CLI runs a separate pipeline, but this server patches unattended, so every version-scoped patch would be silently skipped. |

## Adding one

Make the change in the engine file, then capture it against the upstream file
at the tag in `../FORK_INFO`:

```bash
git clone --depth 1 --branch "$(awk '/^Tag:/ {print $2}' ../FORK_INFO)" \
  https://github.com/MorpheApp/morphe-desktop /tmp/md
diff -u /tmp/md/src/main/kotlin/app/morphe/engine/PatchEngine.kt PatchEngine.kt \
  | sed -e '1s|^--- .*|--- a/PatchEngine.kt|' -e '2s|^+++ .*|+++ b/PatchEngine.kt|' \
  > 000N-short-name.patch
```

Then add a row above, and a line to the "Local deltas from upstream" list in
`../README.md`. Verify it round-trips before committing:

```bash
scripts/sync-morphe-engine.sh "$(awk '/^Tag:/ {print $2}' ../FORK_INFO)"
```

That re-copies upstream and re-applies every patch. A clean round-trip leaves
the engine files unchanged in `git status` — if a `.kt` file shows up as
modified, the patch doesn't reproduce what's actually in the tree.

## Statuses the sync reports

| Status | Meaning |
| --- | --- |
| `clean` | every patch applied exactly |
| `fuzzy` | a patch applied only with reduced context — upstream moved the surrounding lines; check it landed where intended |
| `obsolete` | upstream now contains the change themselves — delete the patch file |
| `failed` | a patch did not apply — re-apply by hand before merging |

## Keep this small

Each delta is a merge conflict waiting to happen. Prefer working *around* the
engine in `app.fdroidserver.patching` over editing it, and reserve patches here
for cases where upstream's behaviour is genuinely wrong for unattended
server-side patching. Better still, send the fix upstream and delete the patch
when it lands.
