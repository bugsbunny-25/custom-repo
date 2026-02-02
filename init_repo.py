#!/usr/bin/env python3
"""
Initialize the F-Droid repository with proper configuration
"""

import subprocess
from pathlib import Path

def init_fdroid_repo():
    """Initialize the F-Droid repository"""
    repo_dir = Path('/srv/fdroid')
    
    # Initialize repository if not already done
    if not (repo_dir / 'config.yml').exists():
        print("Initializing F-Droid repository...")
        try:
            subprocess.run(['fdroid', 'init'], check=True, cwd=repo_dir)
            print("F-Droid repository initialized successfully")
        except subprocess.CalledProcessError as e:
            print(f"Error initializing repository: {e}")
    else:
        print("F-Droid repository already initialized")

if __name__ == '__main__':
    init_fdroid_repo()