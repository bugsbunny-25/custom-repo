#!/usr/bin/env bash
#
# Re-syncs the vendored `app.morphe.engine` package from a morphe-desktop
# release, re-applying the local deltas on top.
#
# Run it by hand any time; CI runs it on a schedule and opens a PR with the
# result (see .github/workflows/morphe-engine-sync.yml).
#
#   scripts/sync-morphe-engine.sh            # sync to upstream's latest release
#   scripts/sync-morphe-engine.sh v1.16.0    # sync to a specific tag
#
# Writes a summary to $GITHUB_OUTPUT when set (CI), otherwise just prints it.
# Exit status: 0 = finished (whether or not anything changed), 1 = error.
set -euo pipefail

UPSTREAM_REPO="MorpheApp/morphe-desktop"
UPSTREAM_ENGINE_PATH="src/main/kotlin/app/morphe/engine"
LOCAL_ENGINE_DIR="morphe-fdroid-server/app/src/main/kotlin/app/morphe/engine"
LOCAL_CATALOG="morphe-fdroid-server/gradle/libs.versions.toml"

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

fork_info="$LOCAL_ENGINE_DIR/FORK_INFO"
current_tag=$(awk '/^Tag:/ {print $2}' "$fork_info")
[ -n "$current_tag" ] || { echo "could not read the current tag from $fork_info" >&2; exit 1; }

# Default to the latest *stable* release. morphe-desktop also tags prereleases
# (v1.15.2-dev.1 and friends); /releases/latest excludes those by definition,
# which is what we want for a repo that patches unattended.
explicit_tag="${1:-}"
target_tag="${explicit_tag:-$(gh api "repos/$UPSTREAM_REPO/releases/latest" --jq .tag_name)}"
[ -n "$target_tag" ] || { echo "could not resolve the upstream release tag" >&2; exit 1; }

echo "vendored : $current_tag"
echo "upstream : $target_tag"

emit() { # key=value pairs for the workflow to consume
  if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$1" >> "$GITHUB_OUTPUT"; fi
}

# Skipped when a tag was named explicitly: re-syncing to the tag already in
# FORK_INFO is how you verify a delta still round-trips (see
# local-deltas/README.md), and it should leave the tree unchanged.
if [ -z "$explicit_tag" ] && [ "$current_tag" = "$target_tag" ]; then
  echo "already on $target_tag - nothing to do"
  emit "changed=false"
  emit "reason=already-current"
  exit 0
fi

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

echo "fetching $UPSTREAM_REPO@$target_tag ..."
git clone --depth 1 --branch "$target_tag" "https://github.com/$UPSTREAM_REPO.git" \
  "$workdir/upstream" --quiet
upstream_engine="$workdir/upstream/$UPSTREAM_ENGINE_PATH"
[ -d "$upstream_engine" ] || {
  echo "upstream has no $UPSTREAM_ENGINE_PATH at $target_tag - the engine may have moved." >&2
  echo "Re-sync by hand and update this script." >&2
  exit 1
}

upstream_sha=$(git -C "$workdir/upstream" rev-parse HEAD)

# Only the files we actually vendor: copying the whole upstream directory would
# drag in the desktop-only pieces this server deliberately left behind (see the
# engine README).
echo "copying vendored files ..."
copied=0
missing=()
while IFS= read -r rel; do
  if [ -f "$upstream_engine/$rel" ]; then
    mkdir -p "$LOCAL_ENGINE_DIR/$(dirname "$rel")"
    cp "$upstream_engine/$rel" "$LOCAL_ENGINE_DIR/$rel"
    copied=$((copied + 1))
  else
    missing+=("$rel")
  fi
done < <(cd "$LOCAL_ENGINE_DIR" && find . -name '*.kt' -type f | sed 's|^\./||' | sort)

echo "copied $copied file(s)"

# Upstream reorganising the package (v1.20.0 moved `PatchExtensions.kt` into
# `patches/` and `util/BundleFormats.kt` into `apk/`) leaves a vendored file
# with no upstream counterpart at its old path, while the pull-in step below
# copies the very same declarations in at the new one - two top-level copies of
# one type, which doesn't compile. Follow an unambiguous rename instead: when
# exactly one upstream file carries that basename, the file moved, so move ours
# with it (keeping its history) and take the fresh copy there.
moved=()
still_missing=()
for rel in ${missing[@]+"${missing[@]}"}; do
  candidates=$(cd "$upstream_engine" && find . -name "$(basename "$rel")" -type f | sed 's|^\./||')
  if [ "$(printf '%s\n' "$candidates" | grep -c .)" = "1" ]; then
    new_rel="$candidates"
    mkdir -p "$LOCAL_ENGINE_DIR/$(dirname "$new_rel")"
    git mv "$LOCAL_ENGINE_DIR/$rel" "$LOCAL_ENGINE_DIR/$new_rel"
    cp "$upstream_engine/$new_rel" "$LOCAL_ENGINE_DIR/$new_rel"
    echo "moved upstream: $rel -> $new_rel"
    moved+=("$rel -> $new_rel")
  else
    still_missing+=("$rel")
  fi
done
missing=(${still_missing[@]+"${still_missing[@]}"})

if [ ${#moved[@]} -gt 0 ]; then
  emit "moved_files=${moved[*]}"
fi

# The copy loop only refreshes files we already have, so a file upstream adds
# is never picked up - even when a vendored file now depends on it (v1.17.0's
# RemotePatchSourceFactory started using the new PullRequestPatchSource, and
# the sync PR failed to compile). Pull in any new upstream file that declares
# something vendored code actually references, repeating until nothing more is
# needed, since a pulled-in file can depend on yet another new one.
declared_names() { # the *top-level* names a .kt file declares - classes,
                   # objects, interfaces, typealiases - plus its public
                   # top-level functions (v1.18.0's PatchEngine started calling
                   # the extension function File.deleteRecursivelyInParallel
                   # from a new util file that declares no type, and the sync PR
                   # failed to compile).
                   #
                   # Top-level only, anchored with no leading whitespace: a
                   # nested name can't be referenced by its simple name anyway,
                   # and matching one drags a whole file in on a word as common
                   # as `Error` (upstream's patches/PatchResolver.kt declares a
                   # nested one).
  grep -oE '^((public|internal|private|sealed|data|enum|abstract|open|annotation|value|inline|fun)[[:space:]]+)*(class|object|interface|typealias)[[:space:]]+[A-Za-z_][A-Za-z0-9_]*' "$1" \
    | awk '{print $NF}'
  grep -E '^((public|internal|inline|suspend|operator|infix)[[:space:]]+)*fun[[:space:]]' "$1" \
    | sed -nE 's/\(.*//; s/^.*[[:space:].>]([A-Za-z_][A-Za-z0-9_]*)$/\1/p'
}

kt_package() { awk '/^package /{print $2; exit}' "$1"; }

# Vendored code with comment lines stripped, kept as *files*: one per package,
# plus one holding the lot. Two reasons this isn't a pipeline into `grep -q`:
#
#   * `grep -q` stops reading at its first match, so the writing end of the
#     pipe takes SIGPIPE - and under `set -o pipefail` that failed the test
#     (exit 141) for names that matched early in the stream while passing for
#     ones near its end. That is exactly how the v1.19.0/v1.19.1/v1.20.0 sync
#     PRs pulled in `PatchedAppStore.kt` yet silently missed `util/Logger.kt`
#     and the dozen other files the reorganised engine needs: all three failed
#     to compile.
#   * a reference resolves without an import only inside the *same* package, so
#     the test has to know which package each line came from.
blob_dir="$workdir/vendored-by-package"
all_vendored="$workdir/vendored-all"
mkdir -p "$blob_dir"
: > "$all_vendored"
index_vendored() { # <vendored .kt file>
  local pkg
  pkg=$(kt_package "$1")
  grep -vE '^[[:space:]]*(//|/?\*)' "$1" \
    | tee -a "$blob_dir/${pkg:-unpackaged}" >> "$all_vendored" || true
}
while IFS= read -r f; do
  index_vendored "$f"
done < <(find "$LOCAL_ENGINE_DIR" -name '*.kt' -type f)

referenced_by_vendored() { # <package of the candidate> <name it declares>
  pkg="$1"
  name="$2"
  # Same package as something we vendor: Kotlin resolves it with no import.
  if [ -f "$blob_dir/$pkg" ] && grep -qw -- "$name" "$blob_dir/$pkg"; then
    return 0
  fi
  # Anything else has to be imported by name (aliased or not), which keeps a
  # desktop-only file from being dragged in by a passing mention of a common
  # word.
  grep -qE "^import ${pkg//./\\.}\.${name}([[:space:]]|\$)" "$all_vendored"
}

added=()
while :; do
  pulled=0
  while IFS= read -r rel; do
    [ -f "$LOCAL_ENGINE_DIR/$rel" ] && continue
    rel_pkg=$(kt_package "$upstream_engine/$rel")
    for name in $(declared_names "$upstream_engine/$rel"); do
      if referenced_by_vendored "$rel_pkg" "$name"; then
        mkdir -p "$LOCAL_ENGINE_DIR/$(dirname "$rel")"
        cp "$upstream_engine/$rel" "$LOCAL_ENGINE_DIR/$rel"
        echo "new upstream file pulled in: $rel (uses $name)"
        added+=("$rel")
        # Its own references count from here on, so whatever *it* newly needs
        # follows on the next pass.
        index_vendored "$LOCAL_ENGINE_DIR/$rel"
        pulled=1
        break
      fi
    done
  done < <(cd "$upstream_engine" && find . -name '*.kt' -type f | sed 's|^\./||' | sort)
  [ "$pulled" -eq 1 ] || break
done

if [ ${#added[@]} -gt 0 ]; then
  emit "added_files=${added[*]}"
fi

# Whatever is still missing after the rename check above was split or deleted
# upstream, which is a real signal, not something to paper over - surface it
# rather than silently keeping our now-orphaned copy.
if [ ${#missing[@]} -gt 0 ]; then
  echo "WARNING: no longer present upstream: ${missing[*]}"
  emit "missing_files=${missing[*]}"
fi

# Re-apply the local deltas (see local-deltas/README.md).
#
# Not --3way: that needs the patch to carry git blob index lines and the file
# to still match the index, and neither holds here - these are cross-repo
# diffs against files this script just overwrote. Exact apply first, then a
# reduced-context retry (-C1) so a delta still lands when upstream only moved
# the surrounding lines around.
delta_status="clean"
delta_notes=""
shopt -s nullglob
for patch in "$LOCAL_ENGINE_DIR"/local-deltas/*.patch; do
  name=$(basename "$patch")
  if git apply --reverse --check --directory="$LOCAL_ENGINE_DIR" "$patch" 2>/dev/null; then
    # Already satisfied by the new upstream code - upstream fixed it themselves.
    echo "delta already present upstream: $name"
    delta_notes="${delta_notes}- \`$name\` is already in upstream - delete it from local-deltas/.\n"
    [ "$delta_status" = "failed" ] || delta_status="obsolete"
  elif git apply --directory="$LOCAL_ENGINE_DIR" "$patch" 2>/dev/null; then
    echo "delta applied: $name"
  elif git apply -C1 --directory="$LOCAL_ENGINE_DIR" "$patch" 2>/dev/null; then
    echo "delta applied with reduced context: $name"
    delta_notes="${delta_notes}- \`$name\` applied only with reduced context - check it landed where intended.\n"
    [ "$delta_status" = "failed" ] || delta_status="fuzzy"
  else
    echo "DELTA FAILED: $name"
    delta_notes="${delta_notes}- \`$name\` did NOT apply - re-apply it by hand before merging.\n"
    delta_status="failed"
  fi
done
shopt -u nullglob

# The engine is compiled against a specific morphe-patcher, and - since
# apk/ApkInspector.kt reads manifests through ARSCLib directly - a specific
# ARSCLib too. Upstream's own catalog is the source of truth for both.
upstream_catalog="$workdir/upstream/gradle/libs.versions.toml"
pin_notes=""
# Sets $pin_version to whatever the pin now reads and appends to $pin_notes -
# globals rather than a value on stdout, since a command substitution would run
# this in a subshell and lose the note (and swallow the progress line below).
sync_pin() { # <version key> <label>
  local key="$1" label="$2" theirs ours
  theirs=$(awk -F'"' -v k="^$key = \"" '$0 ~ k {print $2}' "$upstream_catalog" 2>/dev/null || true)
  ours=$(awk -F'"' -v k="^$key = \"" '$0 ~ k {print $2}' "$LOCAL_CATALOG")
  if [ -n "$theirs" ] && [ "$theirs" != "$ours" ]; then
    echo "$label: $ours -> $theirs"
    # sed over the catalog rather than a template rewrite, to leave the long
    # explanatory comment blocks above the pins untouched.
    sed -i.bak "s|^$key = \"$ours\"|$key = \"$theirs\"|" "$LOCAL_CATALOG"
    rm -f "$LOCAL_CATALOG.bak"
    pin_version="$theirs"
    pin_notes="${pin_notes}$label **$ours -> $theirs**. "
  else
    pin_version="$ours"
    pin_notes="${pin_notes}$label unchanged ($ours). "
  fi
}
sync_pin morphe-patcher morphe-patcher
patcher_version="$pin_version"
sync_pin arsclib ARSCLib
arsclib_version="$pin_version"
patcher_note="${pin_notes% }"

# FORK_INFO records what the engine content was actually taken from.
cat > "$fork_info" <<EOF
Upstream:  https://github.com/$UPSTREAM_REPO
Tag:       $target_tag
Commit:    $upstream_sha
Path:      $UPSTREAM_ENGINE_PATH
Vendored:  $(date -u +%Y-%m-%d)
Patcher:   app.morphe:morphe-patcher $patcher_version (matches morphe-desktop's own catalog pin)
ARSCLib:   com.github.MorpheApp:ARSCLib $arsclib_version (likewise)

See README.md in this directory for what was taken, what was left behind, and
the list of local deltas to re-apply when re-syncing.
EOF

# git status rather than git diff, so a newly pulled-in file (untracked until
# the PR commits it) counts as a change and is listed.
engine_status() {
  git status --porcelain --untracked-files=all -- "$LOCAL_ENGINE_DIR" "$LOCAL_CATALOG"
}

if [ -z "$(engine_status)" ]; then
  echo "no content changes between $current_tag and $target_tag"
  git checkout -- "$fork_info" 2>/dev/null || true
  emit "changed=false"
  emit "reason=no-engine-changes"
  exit 0
fi

changed_files=$(engine_status | cut -c4- | sort -u | sed 's|^|- |')

emit "changed=true"
emit "from_tag=$current_tag"
emit "to_tag=$target_tag"
emit "delta_status=$delta_status"
emit "patcher_note=$patcher_note"
{
  echo "changed_files<<__EOF__"
  echo "$changed_files"
  echo "__EOF__"
  echo "delta_notes<<__EOF__"
  printf '%b' "${delta_notes:-- all local deltas applied cleanly.\n}"
  echo "__EOF__"
} >> "${GITHUB_OUTPUT:-/dev/stdout}"

echo
echo "=== changed ==="
echo "$changed_files"
echo "delta status: $delta_status"
echo "pins: $patcher_note"
