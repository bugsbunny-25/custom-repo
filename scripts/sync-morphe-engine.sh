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

# A vendored file disappearing upstream is a real signal (renamed, split, or
# deleted), not something to paper over - surface it rather than silently
# keeping our now-orphaned copy.
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

# The engine is compiled against a specific morphe-patcher; upstream's own
# catalog is the source of truth for which one.
upstream_patcher=$(awk -F'"' '/^morphe-patcher = "/ {print $2}' \
  "$workdir/upstream/gradle/libs.versions.toml" 2>/dev/null || true)
our_patcher=$(awk -F'"' '/^morphe-patcher = "/ {print $2}' "$LOCAL_CATALOG")
patcher_note="unchanged ($our_patcher)"
if [ -n "$upstream_patcher" ] && [ "$upstream_patcher" != "$our_patcher" ]; then
  echo "morphe-patcher: $our_patcher -> $upstream_patcher"
  # sed over the catalog rather than a template rewrite, to leave the long
  # explanatory comment block above the pin untouched.
  sed -i.bak "s|^morphe-patcher = \"$our_patcher\"|morphe-patcher = \"$upstream_patcher\"|" "$LOCAL_CATALOG"
  rm -f "$LOCAL_CATALOG.bak"
  patcher_note="**$our_patcher -> $upstream_patcher**"
fi

# FORK_INFO records what the engine content was actually taken from.
cat > "$fork_info" <<EOF
Upstream:  https://github.com/$UPSTREAM_REPO
Tag:       $target_tag
Commit:    $upstream_sha
Path:      $UPSTREAM_ENGINE_PATH
Vendored:  $(date -u +%Y-%m-%d)
Patcher:   app.morphe:morphe-patcher ${upstream_patcher:-$our_patcher} (matches morphe-desktop's own catalog pin)

See README.md in this directory for what was taken, what was left behind, and
the list of local deltas to re-apply when re-syncing.
EOF

if git diff --quiet -- "$LOCAL_ENGINE_DIR" "$LOCAL_CATALOG"; then
  echo "no content changes between $current_tag and $target_tag"
  git checkout -- "$fork_info" 2>/dev/null || true
  emit "changed=false"
  emit "reason=no-engine-changes"
  exit 0
fi

changed_files=$(git diff --name-only -- "$LOCAL_ENGINE_DIR" "$LOCAL_CATALOG" | sed 's|^|- |')

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
echo "morphe-patcher: $patcher_note"
