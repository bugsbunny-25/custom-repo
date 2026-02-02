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

### 1. Configure Your Repositories

Edit `config.yml` and add your GitHub repositories. You can use either simple or detailed format:

**Simple format** (uses global defaults):
```yaml
github_repos:
  - "termux/termux-app"
  - "mozilla-mobile/fenix"
```

**Detailed format** (custom settings per repo):
```yaml
# Set global defaults
defaults:
  include_prereleases: true
  include_drafts: false
  max_releases: 5
  enabled: true

github_repos:
  # Simple format - uses defaults above
  - "owner1/standard-app"
  
  # Nightly builds repo - check more releases
  - repo: "nightly-app/android"
    include_prereleases: true
    include_drafts: true
    max_releases: 15
  
  # Stable only repo - no betas
  - repo: "stable-app/android"
    include_prereleases: false
    max_releases: 3
  
  # Temporarily disabled repo
  - repo: "archived/old-app"
    enabled: false
```

**Real-world example:**
```yaml
repo_url: "https://fdroid.yourdomain.com/fdroid"
github_token: ""  # Optional, for higher API limits

defaults:
  include_prereleases: true
  max_releases: 5

github_repos:
  # Termux - include beta releases
  - repo: "termux/termux-app"
    include_prereleases: true
    max_releases: 3
  
  # Signal - stable only
  - repo: "signalapp/Signal-Android"
    include_prereleases: false
    max_releases: 2
  
  # Your app - all releases including nightlies
  - repo: "mycompany/myapp"
    include_prereleases: true
    include_drafts: true
    max_releases: 10
```

### 2. Build and Run

```bash
# Build the Docker image
docker-compose build

# Start the container
docker-compose up -d

# Check logs
docker-compose logs -f
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

## Documentation

- **[PER_REPO_CONFIG.md](PER_REPO_CONFIG.md)** - Comprehensive guide to per-repository configuration
- **[CHANGELOG.md](CHANGELOG.md)** - Feature changes and updates
- **[config.example.yml](config.example.yml)** - Example configuration with various scenarios

## Configuration Options

### config.yml Options

### Global Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `repo_name` | Display name of your repository | "My Custom F-Droid Repo" |
| `repo_description` | Description shown in F-Droid | "Custom F-Droid repository..." |
| `repo_url` | Public URL where repo is accessible | Required |
| `update_interval` | Seconds between update checks | 3600 (1 hour) |
| `github_token` | GitHub personal access token (optional) | "" |
| `max_versions_per_app` | Max APK versions to keep per app (0 = keep all) | 0 |

### Per-Repository Options

Each repository can have individual settings. If not specified, values from `defaults` section are used.

| Option | Description | Default |
|--------|-------------|---------|
| `repo` | GitHub repository (owner/name) | Required |
| `include_prereleases` | Include pre-release versions (beta, alpha, RC) | true |
| `include_drafts` | Include draft releases (not recommended) | false |
| `max_releases` | Max releases to check for this repo (0 = all) | 5 |
| `enabled` | Enable/disable monitoring for this repo | true |

### Configuration Formats

**Simple format:**
```yaml
github_repos:
  - "owner/repo"  # Uses defaults
```

**Detailed format:**
```yaml
github_repos:
  - repo: "owner/repo"
    include_prereleases: true
    include_drafts: false
    max_releases: 10
    enabled: true
```

**Mixed format:**
```yaml
github_repos:
  - "owner/simple-repo"  # Uses defaults
  - repo: "owner/custom-repo"  # Custom settings
    include_prereleases: false
```

### GitHub Token

Without a token, you're limited to 60 API requests per hour. To increase this:

1. Go to https://github.com/settings/tokens
2. Generate a new token (classic)
3. Select scope: `public_repo` (for public repos only)
4. Copy the token and add it to `config.yml`

This increases your rate limit to 5,000 requests/hour.

## Release Types and Per-Repository Configuration

The updater can monitor different types of GitHub releases, and each repository can have its own settings.

### Release Types

**Stable Releases**: Regular releases marked as stable. Always included unless explicitly disabled.

**Pre-releases**: Releases marked as "pre-release" on GitHub:
- Beta versions (e.g., `v1.2.0-beta.1`)
- Release Candidates (e.g., `v1.2.0-rc.2`)
- Alpha versions (e.g., `v1.3.0-alpha`)
- Nightly builds (if tagged as pre-release)

**Draft Releases**: Unpublished releases still in draft status. Usually not recommended.

### Per-Repository Configuration Examples

#### Example 1: Multiple Apps with Different Needs

```yaml
defaults:
  include_prereleases: true
  max_releases: 5

github_repos:
  # Production app - stable releases only
  - repo: "mycompany/production-app"
    include_prereleases: false
    max_releases: 2
  
  # Beta testing app - include all pre-releases
  - repo: "mycompany/beta-app"
    include_prereleases: true
    max_releases: 10
  
  # Internal testing - everything including drafts
  - repo: "mycompany/internal-app"
    include_prereleases: true
    include_drafts: true
    max_releases: 20
  
  # Third-party app - use defaults
  - "termux/termux-app"
```

#### Example 2: Nightly Builds Setup

```yaml
github_repos:
  # App with frequent nightly builds
  - repo: "project/nightly-builds"
    include_prereleases: true    # Include nightlies
    max_releases: 15             # Check last 15 releases
  
  # Keep disk usage manageable
max_versions_per_app: 5  # Keep only 5 versions total
```

#### Example 3: Stable Apps Only

```yaml
defaults:
  include_prereleases: false  # Global: stable only
  max_releases: 3

github_repos:
  - "signal/android"
  - "briarproject/briar"
  - "guardianproject/haven"
  # All use stable-only defaults
```

#### Example 4: Mixed Configuration

```yaml
defaults:
  include_prereleases: true
  max_releases: 5

github_repos:
  # Use defaults
  - "app1/repo"
  - "app2/repo"
  
  # Override: stable only
  - repo: "app3/repo"
    include_prereleases: false
  
  # Override: check more releases
  - repo: "app4/repo"
    max_releases: 15
  
  # Temporarily disabled
  - repo: "old-app/repo"
    enabled: false
```

### Configuration Tips

1. **Start conservative**: Begin with `max_releases: 3` and `include_prereleases: false`
2. **Per-app tuning**: Increase settings only for repos that need it
3. **Monitor disk usage**: Use `max_versions_per_app` if disk space is limited
4. **API rate limits**: More releases = more API calls. Watch your GitHub rate limit
5. **Disable unused repos**: Set `enabled: false` instead of removing from config

## Docker Compose Configuration

### Ports

Change the exposed port in `docker-compose.yml`:

```yaml
ports:
  - "8080:80"  # Change 8080 to your preferred port
```

### Volumes

The configuration uses named volumes for data persistence:
- `fdroid-repo`: APK files and repository data
- `fdroid-metadata`: Application metadata
- `fdroid-tmp`: Temporary files and cache

To use host directories instead:

```yaml
volumes:
  - ./data/repo:/srv/fdroid/repo
  - ./data/metadata:/srv/fdroid/metadata
  - ./data/tmp:/srv/fdroid/tmp
```

## Monitoring and Maintenance

### View Logs

```bash
# All logs
docker-compose logs -f

# Just the updater
docker-compose logs -f fdroid-repo

# Live log file
docker exec fdroid-repo tail -f /var/log/fdroid-updater.log
```

### Force Update Check

```bash
docker exec fdroid-repo python3 -c "
from update_checker import FDroidUpdater
updater = FDroidUpdater()
updater.check_for_updates()
"
```

### Restart Container

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


