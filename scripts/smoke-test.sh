#!/usr/bin/env bash
#
# Smoke test for a built image.
#
#   scripts/smoke-test.sh [image] [--data <dir>]
#
#   image     image to test (default: custom-repo:slim)
#   --data    an existing repo data directory to test against, e.g. a copy of
#             the production /srv/fdroid. When given, the test also asserts
#             that the regenerated index is signed by the SAME key as the one
#             already in that directory - which is the thing F-Droid clients
#             actually pin. Without it the test runs self-contained against a
#             freshly initialized repo, which is what CI does.
#
# What this guards, and why each check exists rather than being assumed:
#
#   1. `jar`, `jarsigner`, `keytool` and `apksigner` are all present.
#      `fdroid update` needs all four, and `jar`/`jarsigner` are JDK-only
#      (jdk.jartool) - they vanish the moment someone "simplifies" the image
#      to a JRE base. That failure happens at publish time, not start-up, so
#      HEALTHCHECK and auto-update rollback do not catch it.
#   2. `fdroid init` really produces a config.yml. It has a fallback path
#      through the removed `pkg_resources`, so a missing examples dir turns
#      into a half-initialized repo.
#   3. `fdroid update` really produces AND signs all three index formats.
#   4. nginx serves the repo paths but NOT the signing keystores or the
#      config.yml that holds their passwords.
#   5. /health and /api/health both answer, since the container HEALTHCHECK
#      depends on both.
#   6. The `builder` role runs and writes a status document.
#
# Exit code 0 means every check passed.

set -euo pipefail

IMAGE="${1:-custom-repo:slim}"
DATA_DIR=""
shift || true
while [ $# -gt 0 ]; do
    case "$1" in
        --data) DATA_DIR="${2:?--data needs a directory}"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

# Apple's `container` on macOS, Docker elsewhere/in CI.
if command -v docker >/dev/null 2>&1; then
    RUNTIME=docker
    ARCH_ARGS=()
elif command -v container >/dev/null 2>&1; then
    RUNTIME=container
    ARCH_ARGS=(--arch arm64)
else
    echo "need either docker or container on PATH" >&2
    exit 2
fi

WORK="$(mktemp -d)"
CLEANUP_IDS=()
cleanup() {
    for id in ${CLEANUP_IDS+"${CLEANUP_IDS[@]}"}; do
        $RUNTIME rm -f "$id" >/dev/null 2>&1 || true
    done
    rm -rf "$WORK"
}
trap cleanup EXIT

pass=0
fail=0
ok()   { printf '  \033[32mok\033[0m   %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; fail=$((fail + 1)); }
note() { printf '       %s\n' "$1"; }
section() { printf '\n== %s\n' "$1"; }

run() { $RUNTIME run --rm "${ARCH_ARGS[@]}" "$@"; }

# ---------------------------------------------------------------------------
section "1. required tools"
# ---------------------------------------------------------------------------
tools_out="$(run "$IMAGE" sh -c '
for t in java jar jarsigner keytool apksigner fdroid git; do
    p=$(command -v "$t" || true)
    echo "$t=${p:-MISSING}"
done
')"
note "$(echo "$tools_out" | tr '\n' ' ')"
for t in java jar jarsigner keytool apksigner fdroid git; do
    if echo "$tools_out" | grep -q "^$t=MISSING$"; then
        bad "$t is missing"
    else
        ok "$t present"
    fi
done

# ---------------------------------------------------------------------------
section "2. fdroid init produces a config.yml"
# ---------------------------------------------------------------------------
if run "$IMAGE" sh -c 'mkdir -p /tmp/fresh && cd /tmp/fresh && fdroid init >/dev/null 2>&1 && test -f config.yml && test -f keystore.p12'; then
    ok "fdroid init wrote config.yml and a keystore"
else
    bad "fdroid init did not initialize a repo (the pkg_resources fallback path?)"
fi

# ---------------------------------------------------------------------------
section "3. fdroid update signs all three index formats"
# ---------------------------------------------------------------------------
if [ -n "$DATA_DIR" ]; then
    cp -R "$DATA_DIR" "$WORK/data"
    note "using a copy of $DATA_DIR"
else
    mkdir -p "$WORK/data"
    run -v "$WORK/data:/d" "$IMAGE" sh -c 'cd /d && fdroid init >/dev/null 2>&1' || true
    note "using a freshly initialized repo"
fi
find "$WORK/data" -name '.DS_Store' -delete 2>/dev/null || true

# The fingerprint already in the data dir, if any, is what clients pin.
# Reads one member out of a jar. The runtime image has no `unzip` on purpose
# (it lives only in the throwaway apksigner build stage), so use python3,
# which is there for fdroidserver anyway.
jar_member() { # <jar path inside container> <member glob>
    run -v "$WORK/data:/d" "$IMAGE" python3 -c '
import fnmatch, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for n in z.namelist():
        if fnmatch.fnmatch(n, sys.argv[2]):
            sys.stdout.buffer.write(z.read(n))
            break
' "$1" "$2" 2>/dev/null || true
}

# The signer fingerprint of a jar, as keytool prints it.
jar_signer_fp() { # <jar path inside container>
    run -v "$WORK/data:/d" "$IMAGE" sh -c '
set -e
python3 -c "
import fnmatch, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for n in z.namelist():
        if fnmatch.fnmatch(n, \"META-INF/*.RSA\") or fnmatch.fnmatch(n, \"META-INF/*.EC\") or fnmatch.fnmatch(n, \"META-INF/*.DSA\"):
            open(\"/tmp/sig.bin\",\"wb\").write(z.read(n)); break
" "$1"
keytool -printcert -file /tmp/sig.bin | grep -i "SHA256:" | head -1
' sh "$1" 2>/dev/null | tr -d ' \r' || true
}

before_fp=""
if [ -f "$WORK/data/repo/index-v1.jar" ]; then
    before_fp="$(jar_signer_fp /d/repo/index-v1.jar)"
    if [ -n "$before_fp" ]; then
        note "existing signer: $before_fp"
    else
        note "could not read the existing signer - fingerprint continuity not checked"
    fi
fi

update_out="$(run -v "$WORK/data:/srv/fdroid" -w /srv/fdroid "$IMAGE" sh -c '
    rm -f repo/index.jar repo/index-v1.jar repo/entry.jar
    fdroid update -c 2>&1
    echo "FDROID_EXIT=$?"
' 2>&1)" || true
echo "$update_out" | grep -E "Creating signed index|FDROID_EXIT|CRITICAL|ERROR" | sed 's/^/       /' || true

if echo "$update_out" | grep -q "FDROID_EXIT=0"; then
    ok "fdroid update exited 0"
else
    bad "fdroid update failed"
fi

for f in index.jar index-v1.jar entry.jar; do
    if [ -f "$WORK/data/repo/$f" ]; then ok "$f was produced"; else bad "$f was NOT produced"; fi
done

# index-v1.jar must be a JAR signature with SHA1 digests (jarsigner), and
# entry.jar a SHA-256 one (apksigner). If someone swaps the runtime for a JRE
# the first of these is what breaks.
if [ -f "$WORK/data/repo/index-v1.jar" ]; then
    man="$(jar_member /d/repo/index-v1.jar 'META-INF/MANIFEST.MF')"
    if echo "$man" | grep -qi "SHA1-Digest"; then
        ok "index-v1.jar carries SHA1 digests (jarsigner, old-Android compatible)"
    else
        bad "index-v1.jar is not SHA1-signed - old clients will reject it"
    fi
fi
if [ -f "$WORK/data/repo/entry.jar" ]; then
    man="$(jar_member /d/repo/entry.jar 'META-INF/MANIFEST.MF')"
    if echo "$man" | grep -qi "SHA-256-Digest"; then
        ok "entry.jar carries SHA-256 digests (apksigner)"
    else
        bad "entry.jar is not SHA-256-signed"
    fi
fi

if [ -n "$before_fp" ]; then
    after_fp="$(jar_signer_fp /d/repo/index-v1.jar)"
    if [ "$before_fp" = "$after_fp" ]; then
        ok "signer fingerprint unchanged - clients keep trusting the repo"
    else
        bad "SIGNER FINGERPRINT CHANGED: $before_fp -> $after_fp"
    fi
fi

# ---------------------------------------------------------------------------
section "4. the builder role runs and reports status"
# ---------------------------------------------------------------------------
mkdir -p "$WORK/db"
builder_out="$(run -v "$WORK/data:/srv/fdroid" -v "$WORK/db:/app/db" \
    -e ARCHIVE_KEEP=3 "$IMAGE" builder run --requests-only 2>&1 || true)"
echo "$builder_out" | tail -3 | sed 's/^/       /'
if [ -f "$WORK/data/.status/builder.json" ] || echo "$builder_out" | grep -q "Nothing to do"; then
    ok "builder ran (no pending requests is a valid outcome)"
else
    bad "builder produced neither a status file nor a 'nothing to do'"
fi

# ---------------------------------------------------------------------------
section "5. serving: health endpoints and secret exposure"
# ---------------------------------------------------------------------------
# A keystore and a config.yml with passwords in it, to prove they are not
# reachable over HTTP.
mkdir -p "$WORK/data"
printf 'keystorepass: SMOKETESTSECRET\nkeypass: SMOKETESTSECRET\n' > "$WORK/data/smoke-config-probe.yml"
cp "$WORK/data/smoke-config-probe.yml" "$WORK/data/keystore-probe.p12"

cid="$($RUNTIME run -d "${ARCH_ARGS[@]}" \
    -v "$WORK/data:/srv/fdroid" -v "$WORK/db:/app/db" \
    -e RUN_SCHEDULERS=false "$IMAGE" server 2>/dev/null)"
CLEANUP_IDS+=("$cid")

# Wait for nginx + the JVM to be up.
up=""
for _ in $(seq 1 40); do
    sleep 2
    if $RUNTIME exec "$cid" curl -fsS http://127.0.0.1/health >/dev/null 2>&1 \
        && $RUNTIME exec "$cid" curl -fsS http://127.0.0.1/api/health >/dev/null 2>&1; then
        up=yes
        break
    fi
done

if [ -n "$up" ]; then
    ok "/health and /api/health both answer (what the HEALTHCHECK needs)"
else
    bad "/health and/or /api/health never came up"
    $RUNTIME logs "$cid" 2>&1 | tail -20 | sed 's/^/       /' || true
fi

probe_status() {
    $RUNTIME exec "$cid" sh -c "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1$1" 2>/dev/null || echo "000"
}

# A repo path is only asserted when the data directory actually has it -
# pointing --data at a single sub-repo (e.g. .../fdroid-data/patched) is a
# legitimate thing to do, and it should not look like a serving regression.
for path in /repo/ /patched/repo/ /patched-tv/repo/ /admin; do
    if [ "$path" != "/admin" ] && [ ! -d "$WORK/data/${path#/}" ]; then
        note "$path skipped - not present in the data directory"
        continue
    fi
    code="$(probe_status "$path")"
    case "$code" in
        200|301|302) ok "$path is served ($code)" ;;
        *) bad "$path returned $code" ;;
    esac
done

# The important one: nothing under the data directory should be downloadable
# just because it sits next to the repo.
for path in /keystore-probe.p12 /smoke-config-probe.yml /config.yml /keystore.p12 /patched-keystore.jks /.requests/ /.status/builder.json; do
    code="$(probe_status "$path")"
    if [ "$code" = "404" ] || [ "$code" = "403" ]; then
        ok "$path is not served ($code)"
    else
        bad "$path IS REACHABLE ($code) - secrets must not be downloadable"
    fi
done

body="$($RUNTIME exec "$cid" sh -c 'curl -s http://127.0.0.1/smoke-config-probe.yml' 2>/dev/null || true)"
if echo "$body" | grep -q SMOKETESTSECRET; then
    bad "a keystore password was served over HTTP"
else
    ok "no keystore password came back over HTTP"
fi

rm -f "$WORK/data/smoke-config-probe.yml" "$WORK/data/keystore-probe.p12"

# ---------------------------------------------------------------------------
printf '\n== result: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
