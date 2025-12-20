#!/usr/bin/env python3
"""Android Locale Manager TUI Application."""

import sys
from pathlib import Path

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent))

from config import Config
from app import LocaleTuiApp


def main():
    """Main entry point."""
    # Find config file
    config_path = Path(__file__).parent.parent / "config.yml"

    if not config_path.exists():
        print(f"Error: Configuration file not found at {config_path}")
        print("Please create config.yml based on the template.")
        sys.exit(1)

    try:
        config = Config.load(config_path)
    except Exception as e:
        print(f"Error loading configuration: {e}")
        sys.exit(1)

    # Validate configuration
    if not config.openai_api_key:
        print("Warning: OPENAI_API_KEY not set. AI translation will not work.")

    # Start application
    app = LocaleTuiApp(config)
    app.run()


if __name__ == "__main__":
    main()
