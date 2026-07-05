# F-Droid Repository Server with GitHub Auto-Updates

A Docker-based F-Droid repository server that automatically monitors GitHub releases for APK files and serves them through a custom F-Droid repository.

## Features

- 🔄 Automatic monitoring of GitHub releases for APK files
- 📦 Serves a complete F-Droid repository
- 🕐 Configurable update interval
- 🔐 Support for private repos via GitHub tokens
- 📊 Logging and health checks
- 🐳 Easy Docker deployment

## Quick Start

### 1. Configuration storage

All configuration lives in a SQLite database (no `config.yml` to hand-edit
anymore). `DB_PATH` is the *directory* the database lives in - the app
creates `app.db` inside it automatically if it doesn't exist yet:

- In the Docker image, `DB_PATH` defaults to `/app/db`, and
  `docker-compose.yml` mounts `${CONFIG_FOLDER}/db` there so it survives
  container recreation.
- For local/dev runs outside Docker, set `DB_PATH` to something like `./data`
  so you don't need the container's `/app` layout.

On first launch, open the admin UI at `/admin` and complete the one-time
setup form (repo name, description, and URL - the URL can't be changed
afterward, since existing F-Droid clients would be pointed at the old one).
GitHub repos and patch targets start out empty; add them from the GitHub and
Patching tabs, and adjust every other setting (update interval, GitHub
token, defaults for new repos, etc.) from the **Settings** tab.

### 2. Build and Run

Building the image compiles the Kotlin app, which depends on the
[morphe-patcher](https://github.com/MorpheApp/morphe-patcher) library.
morphe-patcher is published to GitHub Packages, which requires an
authenticated request even to download a public package — so building
locally needs a GitHub token (any account's token works; it does not need
any special access to `MorpheApp/morphe-patcher`, just needs to be a valid,
authenticated GitHub identity):

```bash
# Build the Docker image (BuildKit secret keeps the token out of image layers)
DOCKER_BUILDKIT=1 docker build \
  --secret id=github_token,env=GITHUB_TOKEN \
  --build-arg GITHUB_ACTOR=<your-github-username> \
  -t custom-repo .

# Start the container
docker-compose up -d

# Check logs
docker-compose logs -f
```

(CI builds via `.github/workflows/docker-publish.yml` use the workflow's own
`secrets.GITHUB_TOKEN`/`github.actor` automatically — no setup needed there.)

If you're using Apple's `container` CLI instead of Docker, use
`local.Dockerfile` instead — its builder doesn't support BuildKit secret
mounts, so the token is passed as a plain build arg there:

```bash
container build \
  --build-arg GITHUB_ACTOR=<your-github-username> \
  --build-arg GITHUB_TOKEN=<your-github-token> \
  -t custom-repo -f local.Dockerfile .
```

The repository will be available at `http://localhost:8080`

### 3. Configure Your Nginx Reverse Proxy

Add this to your nginx configuration:

```nginx
server {
    listen 80;
    server_name fdroid.yourdomain.com;  # Change this
    
    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # Increase timeout for large APK downloads
        proxy_read_timeout 300s;
        proxy_connect_timeout 75s;
    }
}
```

For HTTPS (recommended):

```nginx
server {
    listen 443 ssl http2;
    server_name fdroid.yourdomain.com;
    
    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;
    
    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        proxy_read_timeout 300s;
        proxy_connect_timeout 75s;
    }
}

server {
    listen 80;
    server_name fdroid.yourdomain.com;
    return 301 https://$server_name$request_uri;
}
```

Then reload nginx:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### 4. Add Repository to F-Droid Client

1. Open F-Droid app on your Android device
2. Go to Settings → Repositories
3. Tap the "+" button
4. Enter your repository URL: `https://fdroid.yourdomain.com/repo`
5. Tap "Add"

The repository will sync and your apps will appear in F-Droid!

## APK Patching (Morphe + APKMirror)

The admin UI at `/admin` has two tabs:

- **GitHub** — the original flow described above (monitors GitHub releases).
- **Patching** — a separate pipeline that watches APKMirror app pages for new
  versions, and when a version matches a patch attached to that app, downloads
  the APK, patches it with Morphe, and publishes the result to its own F-Droid
  repo served at `/patched/repo` (add it as a second repo URL in the F-Droid
  client). Only the newest **3 versions** per (app, patch) pair are kept —
  older ones are deleted automatically.

The whole server (admin UI, GitHub release checker, APKMirror scraper, and
patch pipeline) is a single Kotlin/JVM application
(`morphe-fdroid-server/`) that calls the
[morphe-patcher](https://github.com/MorpheApp/morphe-patcher) library
directly — there's no external Morphe CLI process, no command templates, and
no jars to auto-download. `fdroidserver` (the `fdroid` CLI) is the only
remaining Python dependency, used only to generate/sign the repo index.

### APK bundles (.apkm/.xapk/.apks)

Some apps (usually large ones) are only published on APKMirror as a bundle
(`base.apk` plus split APKs) rather than a single installable `.apk`. When a
patch target's download turns out to be a bundle, morphe-patcher's
`ApkMerger` merges **all** the splits it contains into one installable APK
before patching — there's no per-arch/density/language selection to
configure. If APKMirror also offers a plain `.apk` for that version, it's
preferred over the bundle.

### Patch library

`.mpp` patch files are managed in a **library**, separate from the apps that
use them: upload a patch once in the Patching tab's "Patch Library" section,
then attach it to any number of apps in "Patched Apps" (each attachment has
its own supported-version list). Re-uploading a patch's content via "Update"
replaces the file for every app using it, invalidates their cached results,
and resets any per-app sub-patch/option overrides, so the next check
re-patches with the new version's defaults.

Each library patch also has a **"View Packages"** button that inspects the
`.mpp` file directly (via `loadPatchesFromJar`) and shows, for every
individual patch inside it, its name/description and which app package(s)
and version(s) it supports (a single `.mpp` can bundle multiple named
patches with different compatible packages, like a ReVanced patch bundle).
This is read live from the file each time — nothing is cached.

### Per-app sub-patch and option overrides

A `.mpp` file can bundle several named sub-patches, each individually
enabled or disabled by default, and each with its own configurable options
(with default values) — think ReVanced's "Custom branding", "Hide ads", etc.
On a Patch Target's **"Patches"** button, each attached patch has a
**"Configure"** button showing every sub-patch the `.mpp` contains, its
default enabled/disabled state, and its options with their default values.
You can:

- Tick/untick a sub-patch to force it on or off, away from the `.mpp`'s own
  default.
- Type a value into an option field to override its default (leave it
  matching the shown default, or empty, to just use the `.mpp`'s value).

Only the fields you actually change are saved as overrides
(`patch_selection` / `option_overrides` on that attachment in the database);
everything else keeps following the `.mpp`'s own defaults. These overrides
are applied in-process by setting each `Patch`/`Option`'s value directly
before running the patcher — no shell flags involved.

### Setting it up

1. **Upload a patch** in the Patching tab's Patch Library: an id, display
   name, the `.mpp` file, and an optional version label.

2. **Add a patch target**: a display name, the APKMirror app page URL, and
   (optionally) the expected package name.

3. **Attach the patch to the target** via its "Patches" button, specifying
   which app version(s) it supports (comma-separated, or `*` for any
   version). The same library patch can be attached to multiple app targets.
   **Leave the versions field empty** to instead read supported versions
   straight from the `.mpp` file itself, filtered to the target's
   `package_name` — useful when you don't want to duplicate a patch's own
   compatibility info into the config.

4. The patch scheduler polls on the same interval as the GitHub checker
   (override with `patch_check_interval` on the Settings tab) — each cycle it
   checks every patch target's APKMirror page. When it finds a version
   matching an attached patch that hasn't been processed yet, it downloads,
   merges (if it's a bundle), patches, signs, and updates the
   `/patched/repo` index.

### Caveats

- **APKMirror has no official API.** New versions are discovered by scraping
  its HTML pages, which is fragile (breaks if APKMirror changes markup) and
  against APKMirror's Terms of Service. Proceed at your own risk.
- Logs for this pipeline are part of the main app process's logs (`docker
  exec fdroid-repo tail -f /var/log/supervisor/fdroid-server.log` or
  `docker-compose logs -f`).

## Configuration Options

Everything below is configured from the admin UI at `/admin` (Settings tab
for global options, GitHub tab for per-repo options) - there is no
`config.yml` file anymore.

### Global settings (Settings tab)

| Option | Description | Default |
|--------|-------------|---------|
| `repo_name` | Display name of your repository | Set during first-run setup; editable after |
| `repo_description` | Description shown in F-Droid | Set during first-run setup; editable after |
| `repo_url` | Public base URL your domain is served on (no path, e.g. `https://fdroid.example.com`) - `/repo` and `/patched/repo` are appended automatically for the main and patched F-Droid repos | Set during first-run setup; **locked** after |
| `update_interval` | Seconds between update checks | 3600 (1 hour) |
| `patch_check_interval` | Seconds between APKMirror patch checks (optional) | Falls back to `update_interval` |
| `github_token` | GitHub personal access token (optional) | "" |
| `apk_pattern` | Default regex used to match APK filenames | `.*\.apk$` |
| `max_versions_per_app` | Max APK versions to keep per app (0 = keep all) | 0 |
| `log_level` | Log verbosity | INFO |

### Defaults for new GitHub repos (Settings tab)

Applied to a repo unless it overrides the value itself (GitHub tab).

| Option | Description | Default |
|--------|-------------|---------|
| `include_prereleases` | Include pre-release versions (beta, alpha, RC) | true |
| `include_drafts` | Include draft releases (not recommended) | false |
| `max_releases` | Max releases to check | 5 |
| `enabled` | Enable/disable monitoring by default | true |

### GitHub Token

Without a token, you're limited to 60 API requests per hour. To increase this:

1. Go to https://github.com/settings/tokens
2. Generate a new token (classic)
3. Select scope: `public_repo` (for public repos only)
4. Paste it into the **GitHub token** field on the Settings tab and save.

This increases your rate limit to 5,000 requests/hour.

### Release Types

**Stable Releases**: Regular releases marked as stable. Always included unless explicitly disabled.

**Pre-releases**: Releases marked as "pre-release" on GitHub:
- Beta versions (e.g., `v1.2.0-beta.1`)
- Release Candidates (e.g., `v1.2.0-rc.2`)
- Alpha versions (e.g., `v1.3.0-alpha`)
- Nightly builds (if tagged as pre-release)

**Draft Releases**: Unpublished releases still in draft status. Usually not recommended.

### Configuration Tips

1. **Start conservative**: Begin with a low `max_releases` and `include_prereleases` off
2. **Per-app tuning**: Override the GitHub tab's defaults only for repos that need it
3. **Monitor disk usage**: Use `max_versions_per_app` if disk space is limited
4. **API rate limits**: More releases = more API calls. Watch your GitHub rate limit
5. **Disable unused repos**: Toggle `enabled` off instead of removing the repo

## Docker Compose Configuration

### Ports

Change the exposed port in `docker-compose.yml`:

```yaml
ports:
  - "8080:80"  # Change 8080 to your preferred port
```

### Volumes

`docker-compose.yml` uses host bind mounts under `${CONFIG_FOLDER}` (set that
env var, or edit the paths directly) for data persistence:
- `${CONFIG_FOLDER}/fdroid-data/` → `/srv/fdroid` — repo, patched-repo, and
  metadata (everything the `fdroid` CLI manages)
- `${CONFIG_FOLDER}/db` → `/app/db` — the SQLite database (all settings,
  GitHub repos, patch library/targets, checked-release history)
- `${CONFIG_FOLDER}/logs` → `/var/log` — optional, for persisting logs across
  container recreation

## Monitoring and Maintenance

### View Logs

```bash
# All logs
docker-compose logs -f

# Just the updater
docker-compose logs -f fdroid-repo

# Live log file
docker exec fdroid-repo tail -f /var/log/supervisor/fdroid-server.log
```

### Force Update Check

Both the GitHub and Patching schedulers run their first check immediately on
process startup, so restarting the container triggers an immediate check of
everything without waiting for the configured interval:

```bash
docker-compose restart
```

### Health Check

```bash
curl http://localhost:8080/health
```

## Troubleshooting

### No APKs appearing

1. Check that GitHub repos have releases with APK files
2. Check logs: `docker-compose logs -f`
3. Verify GitHub token is valid (if using one)
4. Check API rate limits: https://api.github.com/rate_limit


