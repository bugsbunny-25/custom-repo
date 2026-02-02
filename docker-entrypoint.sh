#!/bin/sh
set -e

# 1. Create the supervisor log directory (Fixes your error)
mkdir -p /var/log/supervisor

# 2. Check if repo needs initialization (Fixes volume mount issues)
# We check if config.yml exists to see if the repo is initialized
if [ ! -f "/srv/fdroid/config.yml" ]; then
    echo "Initializing F-Droid repository..."
    python3 /app/init_repo.py
else
    echo "F-Droid repository already initialized."
fi

# 3. Hand over control to the CMD (Supervisord)
exec "$@"