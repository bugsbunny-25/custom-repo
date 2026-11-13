#!/bin/sh
set -eu

# ---------------------------------------------------------------------------
# Two roles, one image
# ---------------------------------------------------------------------------
#   server            always-on: nginx (repo + admin proxy) and the admin JVM.
#                     No scheduler loops - it does not patch or run `fdroid
#                     update`, so its memory stays flat.
#   builder [run]     one-shot: does the GitHub checks, patching and
#                     `fdroid update`, writes its status, and exits. Its peak
#                     memory is returned to the host the moment it exits.
#
# Anything else is exec'd as-is, so `podman run ... <image> sh` still works.
#
# PID 1 is the JVM, not a supervisor. supervisord used to sit in front of both
# processes and restart them in place, which hid failures from the container
# runtime - exactly the failures `podman auto-update` needs to see in order to
# roll a bad image back. Now: if the JVM dies the container exits and podman
# restarts (or rolls back); if nginx dies the HEALTHCHECK fails and podman
# restarts it. That also drops supervisord's own interpreter from the idle
# footprint.
#
# One visible consequence: the app's log no longer lands in
# /var/log/supervisor/fdroid-server.log. It goes to the container's stdout, so
# read it with `podman logs fdroid-repo` or journald. nginx still logs to
# /var/log/nginx/ as before.

ROLE="${1:-server}"

# SQLite DB directory (DB_PATH points at the directory, not the db file
# itself - the app creates app.db inside it if missing). The Dockerfile
# already creates this, but a mounted volume can shadow it (an empty/new
# bind mount replaces the image's dir), so ensure it still exists before the
# JVM starts. Same for the request/status dirs the builder trigger uses, and
# for /keys.
mkdir -p "${DB_PATH:-/app/db}"
mkdir -p "${REPO_PATH:-/srv/fdroid}/.requests" "${REPO_PATH:-/srv/fdroid}/.status"
mkdir -p "${KEYS_PATH:-/keys}"

# Shared JVM flags. SerialGC because both roles are single-digit-thread
# workloads where G1's concurrent threads and region bookkeeping are pure
# overhead; it also gives back heap to the OS more readily. The metaspace and
# thread-stack caps matter as much as -Xmx here: an uncapped metaspace was a
# meaningful part of the old ~497 MB resident footprint.
#
# These are deliberately set here, on the command line, and NOT in
# JAVA_TOOL_OPTIONS or the environment: the patch worker is spawned with
# ProcessBuilder and inherits the environment, so anything set that way would
# also clamp patch runs, which need a much bigger heap. The worker's own
# ceiling is PATCH_WORKER_XMX, read by PatchWorkerLauncher.
common_jvm_flags() {
    printf '%s' \
        "-XX:+UseSerialGC \
         -XX:MaxMetaspaceSize=${JVM_MAX_METASPACE:-128m} \
         -Xss${JVM_THREAD_STACK:-512k} \
         -XX:+ExitOnOutOfMemoryError"
}

start_nginx() {
    mkdir -p /var/log/nginx /var/lib/nginx/body
    # Fail the whole container on a bad config rather than serving nothing.
    nginx -t
    nginx
}

case "$ROLE" in
    server)
        start_nginx
        # shellcheck disable=SC2046 # word splitting of the flag list is intended
        exec java \
            -Xmx"${SERVER_JVM_XMX:-256m}" \
            $(common_jvm_flags) \
            -jar /app/app.jar server
        ;;

    builder)
        shift || true
        # The builder never serves traffic, so no nginx here. It still writes
        # into /srv/fdroid, so it must not run concurrently with another
        # builder - the lock that enforces that is inside the app
        # (BuildLock), not here, so a `.path`-unit-triggered run and a timer
        # run can safely race.
        # shellcheck disable=SC2046
        exec java \
            -Xmx"${BUILDER_JVM_XMX:-256m}" \
            $(common_jvm_flags) \
            -jar /app/app.jar builder ${@:+"$@"}
        ;;

    *)
        exec "$@"
        ;;
esac
