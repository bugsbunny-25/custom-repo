#!/bin/sh
set -e

mkdir -p /var/log/supervisor
mkdir -p /var/log/nginx

# SQLite DB directory (DB_PATH points at the directory, not the db file
# itself - the app creates app.db inside it if missing). The Dockerfile
# already creates this, but a mounted volume can shadow it (an empty/new
# bind mount replaces the image's dir), so ensure it still exists before the
# JVM starts.
mkdir -p "${DB_PATH:-/app/db}"

# F-Droid repo initialization (fdroid init for both /srv/fdroid and
# /srv/fdroid/patched) now happens inside the Kotlin app itself on startup
# (see FdroidRepoManager.initRepo, called from Main.kt) rather than a
# separate entrypoint step - it's idempotent (checks for an existing
# config.yml first) so it's safe to run on every container start.

exec "$@"
