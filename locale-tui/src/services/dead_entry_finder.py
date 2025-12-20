"""Dead entry finder service."""

import re
from pathlib import Path
from typing import Set, TYPE_CHECKING
import glob as glob_module

if TYPE_CHECKING:
    from models.entry import TranslationEntry


class DeadEntryFinder:
    """Detect unreferenced translation entries in code."""

    # Patterns to match R.string.xxx, stringResource(R.string.xxx), etc.
    PATTERNS = [
        r"R\.string\.(\w+)",
        r"getString\s*\(\s*R\.string\.(\w+)",
        r"stringResource\s*\(\s*R\.string\.(\w+)",
        r"@string/(\w+)",
    ]

    # System reserved keys that should not be marked as dead
    RESERVED_KEYS = {"app_name"}

    def __init__(self, project_root: Path):
        self.project_root = project_root
        self.compiled_patterns = [re.compile(p) for p in self.PATTERNS]

    def find_referenced_keys(self, source_patterns: list[str]) -> Set[str]:
        """Find all referenced string keys from source code."""
        referenced = set()

        for pattern in source_patterns:
            full_pattern = str(self.project_root / pattern)
            for file_path in glob_module.glob(full_pattern, recursive=True):
                referenced.update(self._extract_keys_from_file(Path(file_path)))

        # Also check layout XML files
        layout_patterns = [
            "**/res/layout*/*.xml",
            "**/res/menu/*.xml",
            "**/res/navigation/*.xml",
        ]
        for pattern in layout_patterns:
            full_pattern = str(self.project_root / pattern)
            for file_path in glob_module.glob(full_pattern, recursive=True):
                referenced.update(self._extract_keys_from_file(Path(file_path)))

        return referenced

    def _extract_keys_from_file(self, file_path: Path) -> Set[str]:
        """Extract string keys from a single file."""
        keys = set()
        try:
            content = file_path.read_text(encoding="utf-8")
            for pattern in self.compiled_patterns:
                matches = pattern.findall(content)
                keys.update(matches)
        except Exception:
            pass
        return keys

    def mark_dead_entries(
        self, entries: list["TranslationEntry"], source_patterns: list[str]
    ) -> int:
        """Mark unreferenced entries, returns dead entry count."""
        referenced = self.find_referenced_keys(source_patterns)
        dead_count = 0

        for entry in entries:
            # Reserved keys are never marked as dead
            if entry.key in self.RESERVED_KEYS:
                entry.is_dead = False
                continue

            entry.is_dead = entry.key not in referenced
            if entry.is_dead:
                dead_count += 1

        return dead_count
