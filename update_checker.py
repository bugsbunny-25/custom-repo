#!/usr/bin/env python3
"""
GitHub Release Monitor for F-Droid Repository
Checks GitHub releases for APK files and updates the F-Droid repo
"""

import os
import sys
import time
from ruamel.yaml import YAML
import json
import hashlib
import requests
import logging
import subprocess
import schedule
from pathlib import Path
from datetime import datetime

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('/var/log/fdroid-updater.log'),
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger(__name__)
yaml = YAML()
yaml.preserve_quotes = True

class FDroidUpdater:
    def __init__(self, config_path='/app/config.yml'):
        with open(config_path, 'r') as f:
            self.config = yaml.load(f)
        
        self.repo_dir = Path('/srv/fdroid/repo')
        self.metadata_dir = Path('/srv/fdroid/metadata')
        self.cache_file = Path('/srv/fdroid/tmp/cache.json')
        
        # Set up GitHub API headers
        self.headers = {'Accept': 'application/vnd.github.v3+json'}
        if self.config.get('github_token'):
            self.headers['Authorization'] = f"token {self.config['github_token']}"
        
        # Parse repo configurations
        self.repos = self._parse_repo_configs()
        
        # Load cache
        self.cache = self._load_cache()
    
    def _parse_repo_configs(self):
        """Parse repository configurations, applying defaults where needed"""
        repos = []
        defaults = self.config.get('defaults', {})
        
        for repo_config in self.config.get('github_repos', []):
            if isinstance(repo_config, str):
                # Simple format: "owner/repo"
                repo = {
                    'repo': repo_config,
                    'include_prereleases': defaults.get('include_prereleases', True),
                    'include_drafts': defaults.get('include_drafts', False),
                    'max_releases': defaults.get('max_releases', 5),
                    'enabled': defaults.get('enabled', True)
                }
            else:
                # Detailed format: dict with custom settings
                repo = {
                    'repo': repo_config.get('repo'),
                    'include_prereleases': repo_config.get('include_prereleases', 
                                                           defaults.get('include_prereleases', True)),
                    'include_drafts': repo_config.get('include_drafts', 
                                                      defaults.get('include_drafts', False)),
                    'max_releases': repo_config.get('max_releases', 
                                                    defaults.get('max_releases', 5)),
                    'enabled': repo_config.get('enabled', 
                                              defaults.get('enabled', True))
                }
            
            if repo['repo'] and repo['enabled']:
                repos.append(repo)
                logger.info(f"Configured {repo['repo']}: prereleases={repo['include_prereleases']}, "
                          f"drafts={repo['include_drafts']}, max_releases={repo['max_releases']}")
            elif repo['repo']:
                logger.info(f"Skipping disabled repo: {repo['repo']}")
        
        return repos
    
    def _load_cache(self):
        """Load the cache of downloaded releases"""
        if self.cache_file.exists():
            try:
                with open(self.cache_file, 'r') as f:
                    return json.load(f)
            except Exception as e:
                logger.error(f"Error loading cache: {e}")
        return {}
    
    def _save_cache(self):
        """Save the cache of downloaded releases"""
        try:
            self.cache_file.parent.mkdir(parents=True, exist_ok=True)
            with open(self.cache_file, 'w') as f:
                json.dump(self.cache, f, indent=2)
        except Exception as e:
            logger.error(f"Error saving cache: {e}")
    
    def _get_releases(self, repo_name, repo_config):
        """Get releases from GitHub based on per-repo configuration"""
        url = f"https://api.github.com/repos/{repo_name}/releases"
        
        # Get max_releases from repo config
        max_releases = repo_config.get('max_releases', 5)
        per_page = 100 if max_releases == 0 else min(max_releases, 100)
        
        params = {'per_page': per_page}
        
        try:
            response = requests.get(url, headers=self.headers, params=params, timeout=30)
            response.raise_for_status()
            releases = response.json()
            
            if not releases:
                logger.warning(f"No releases found for {repo_name}")
                return []
            
            # Filter releases based on repo-specific configuration
            include_prereleases = repo_config.get('include_prereleases', True)
            include_drafts = repo_config.get('include_drafts', False)
            
            filtered_releases = []
            for release in releases:
                # Skip drafts unless explicitly enabled for this repo
                if release.get('draft', False) and not include_drafts:
                    continue
                
                # Skip pre-releases unless explicitly enabled for this repo
                if release.get('prerelease', False) and not include_prereleases:
                    continue
                
                filtered_releases.append(release)
                
                # Limit to max_releases if set
                if max_releases > 0 and len(filtered_releases) >= max_releases:
                    break
            
            logger.info(f"{repo_name}: Found {len(filtered_releases)} release(s) to process "
                       f"(prereleases: {include_prereleases}, drafts: {include_drafts})")
            return filtered_releases
            
        except requests.exceptions.HTTPError as e:
            if e.response.status_code == 404:
                logger.warning(f"No releases found for {repo_name}")
            else:
                logger.error(f"Error fetching releases for {repo_name}: {e}")
            return []
        except Exception as e:
            logger.error(f"Error fetching releases for {repo_name}: {e}")
            return []
    
    def _download_apk(self, url, filename):
        """Download an APK file"""
        filepath = self.repo_dir / filename
        
        try:
            logger.info(f"Downloading {filename}...")
            response = requests.get(url, stream=True, timeout=300)
            response.raise_for_status()
            
            with open(filepath, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    f.write(chunk)
            
            logger.info(f"Downloaded {filename}")
            return True
        except Exception as e:
            logger.error(f"Error downloading {filename}: {e}")
            if filepath.exists():
                filepath.unlink()
            return False
    
    def _compute_hash(self, filepath):
        """Compute SHA256 hash of a file"""
        sha256_hash = hashlib.sha256()
        with open(filepath, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)
        return sha256_hash.hexdigest()
    
    def _update_fdroid_repo(self):
        """Update the F-Droid repository index"""
        try:
            logger.info("Updating F-Droid repository index...")
            os.chdir('/srv/fdroid')
            result = subprocess.run(
                ['fdroid', 'update', '-c'],
                capture_output=True,
                text=True,
                timeout=300
            )
            
            if result.returncode == 0:
                logger.info("F-Droid repository updated successfully")
                return True
            else:
                logger.error(f"Error updating F-Droid repo: {result.stderr}")
                return False
        except Exception as e:
            logger.error(f"Error updating F-Droid repo: {e}")
            return False
    
    def check_for_updates(self):
        """Check all configured repos for updates"""
        logger.info("Starting update check...")
        updated = False
        
        for repo_config in self.repos:
            repo_name = repo_config['repo']
            try:
                logger.info(f"Checking {repo_name}...")
                releases = self._get_releases(repo_name, repo_config)
                
                if not releases:
                    continue
                
                # Initialize cache for this repo if needed
                if repo_name not in self.cache:
                    self.cache[repo_name] = {'processed_releases': {}}
                
                # Process each release
                for release in releases:
                    release_tag = release['tag_name']
                    release_id = str(release['id'])
                    release_type = self._get_release_type(release)
                    
                    # Check if we've already processed this release
                    if release_id in self.cache[repo_name].get('processed_releases', {}):
                        logger.debug(f"{repo_name}: Release {release_tag} ({release_type}) already processed")
                        continue
                    
                    logger.info(f"{repo_name}: Processing {release_type} release {release_tag}")
                    
                    # Look for APK files in assets
                    apk_assets = [
                        asset for asset in release.get('assets', [])
                        if asset['name'].endswith('.apk')
                    ]
                    
                    if not apk_assets:
                        logger.warning(f"{repo_name}: No APK files found in release {release_tag}")
                        # Still mark as processed to avoid checking again
                        self.cache[repo_name]['processed_releases'][release_id] = {
                            'tag': release_tag,
                            'type': release_type,
                            'processed_at': datetime.now().isoformat(),
                            'apks_found': 0
                        }
                        continue
                    
                    # Download new APKs
                    downloaded_count = 0
                    for asset in apk_assets:
                        asset_name = asset['name']
                        download_url = asset['browser_download_url']
                        
                        if self._download_apk(download_url, f"{release_id}-{asset_name}"):
                            downloaded_count += 1
                            updated = True
                    
                    # Mark this release as processed
                    self.cache[repo_name]['processed_releases'][release_id] = {
                        'tag': release_tag,
                        'type': release_type,
                        'processed_at': datetime.now().isoformat(),
                        'apks_found': len(apk_assets),
                        'apks_downloaded': downloaded_count
                    }
                    
                    logger.info(f"{repo_name}: Downloaded {downloaded_count}/{len(apk_assets)} APK(s) from {release_tag}")
                
                # Update cache metadata
                self.cache[repo_name]['last_checked'] = datetime.now().isoformat()
                self.cache[repo_name]['config'] = {
                    'include_prereleases': repo_config['include_prereleases'],
                    'include_drafts': repo_config['include_drafts'],
                    'max_releases': repo_config['max_releases']
                }
                
            except Exception as e:
                logger.error(f"Error processing {repo_name}: {e}")
        
        # Update F-Droid index if any APKs were downloaded
        if updated:
            self._update_fdroid_repo()
            # Cleanup old APKs if configured
            self.cleanup_old_apks()
        
        self._save_cache()
        logger.info("Update check completed")
    
    def _get_release_type(self, release):
        """Determine the type of release"""
        if release.get('draft', False):
            return 'draft'
        elif release.get('prerelease', False):
            return 'pre-release'
        else:
            return 'stable'
    
    def cleanup_old_apks(self):
        """Remove old APK versions if cleanup is enabled"""
        max_versions = self.config.get('max_versions_per_app', 0)
        
        if max_versions <= 0:
            return  # Cleanup disabled
        
        logger.info(f"Cleaning up old APKs (keeping max {max_versions} versions per app)...")
        
        try:
            # Group APKs by package name (simplified version)
            # This is a basic implementation - you might want to enhance it
            apk_files = list(self.repo_dir.glob('*.apk'))
            
            # Sort by modification time (newest first)
            apk_files.sort(key=lambda x: x.stat().st_mtime, reverse=True)
            
            # Keep only the newest max_versions files
            if len(apk_files) > max_versions:
                for old_apk in apk_files[max_versions:]:
                    logger.info(f"Removing old APK: {old_apk.name}")
                    old_apk.unlink()
        
        except Exception as e:
            logger.error(f"Error during cleanup: {e}")
    
    def update_fdroid_config(self):
        # Open config.yml for F-Droid
        fdroid_config_path = Path('/srv/fdroid/config.yml')
        if fdroid_config_path.exists():
            with open(fdroid_config_path, 'r') as f:
                fdroid_config = yaml.load(f)
        else:
            fdroid_config = {}

        fdroid_config['repo_name'] = self.config.get('repo_name', 'My F-Droid Repo')
        fdroid_config['repo_description'] = self.config.get('repo_description', 'Custom F-Droid Repository')
        fdroid_config['repo_url'] = self.config.get('repo_url', 'https://example.com/fdroid')
        fdroid_config['repo_icon'] = self.config.get('repo_icon', 'icon.png')
        fdroid_config['archive_older'] = 3
        fdroid_config['make_current_version_link'] = True
        fdroid_config['update_stats'] = True

        with open(fdroid_config_path, 'w') as f:
            yaml.dump(fdroid_config, f)
        
        self._update_fdroid_repo()
        print("F-Droid repository configuration created")
    
    def run_scheduler(self):
        """Run the update checker on a schedule"""
        interval = self.config.get('update_interval', 3600)
        logger.info(f"Starting scheduler (checking every {interval} seconds)...")
        
        # Run immediately on start
        self.check_for_updates()
        
        # Schedule periodic checks
        schedule.every(interval).seconds.do(self.check_for_updates)
        
        while True:
            schedule.run_pending()
            time.sleep(60)

if __name__ == '__main__':
    try:
        updater = FDroidUpdater()
        updater.update_fdroid_config()
        updater.run_scheduler()
    except KeyboardInterrupt:
        logger.info("Shutting down...")
        sys.exit(0)
    except Exception as e:
        logger.error(f"Fatal error: {e}")
        sys.exit(1)