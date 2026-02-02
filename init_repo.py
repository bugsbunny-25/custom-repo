#!/usr/bin/env python3
"""
Initialize the F-Droid repository with proper configuration
"""

import os
from ruamel.yaml import YAML
import subprocess
from pathlib import Path

def init_fdroid_repo():
    """Initialize the F-Droid repository"""
    repo_dir = Path('/srv/fdroid')
    config_file = Path('/app/config.yml')
    
    # Initialize repository if not already done
    if not (repo_dir / 'repo').exists():
        print("Initializing F-Droid repository...")
        try:
            subprocess.run(['fdroid', 'init'], check=True, cwd=repo_dir)
            print("F-Droid repository initialized successfully")
        except subprocess.CalledProcessError as e:
            print(f"Error initializing repository: {e}")
    else:
        print("F-Droid repository already initialized")
    
    yaml = YAML()
    yaml.preserve_quotes = True
    
    # Load configuration
    with open(config_file, 'r') as f:
        config = yaml.load(f)
    
    os.chdir(repo_dir)
    
    # Create config.yml for F-Droid
    fdroid_config = {
        'repo_name': config.get('repo_name', 'My F-Droid Repo'),
        'repo_description': config.get('repo_description', 'Custom F-Droid Repository'),
        'repo_url': config.get('repo_url', 'https://example.com/fdroid'),
        'repo_icon': config.get('repo_icon', 'icon.png'),
        'archive_older': 3,
        'make_current_version_link': True,
        'update_stats': True,
    }

    config_path = repo_dir / 'config.yml'
    with open(config_path, 'w') as f:
        yaml.dump(fdroid_config, f)
    
    print("F-Droid repository configuration created")

if __name__ == '__main__':
    init_fdroid_repo()