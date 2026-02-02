#!/bin/sh
set -e

mkdir -p /var/log/supervisor
mkdir -p /var/log/nginx


if [ ! -f "/srv/fdroid/config.yml" ]; then
    echo "Initializing F-Droid repository..."
    python3 /app/init_repo.py
else
    echo "F-Droid repository already initialized."
fi

exec "$@"