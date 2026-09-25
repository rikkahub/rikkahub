"""Unit tests for translation validation helpers (offline)."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from services.validation import (
    escape_android_quotes,
    extract_placeholders,
    validate_translation,
)


def test_extract_placeholders():
    assert extract_placeholders("%1$s has %2$d items, %.1f%%") == sorted(
        ["%1$s", "%2$d", "%.1f", "%%"]
    )
    assert extract_placeholders("no placeholders") == []


def test_placeholder_reorder_is_allowed():
    assert validate_translation("%1$s sent %2$d", "%2$d أرسل %1$s") is None


def test_placeholder_mismatch():
    assert validate_translation("Welcome, %1$s!", "مرحبًا!") is not None
    assert validate_translation("%1$d%%", "%1$d") is not None


def test_line_break_mismatch():
    assert validate_translation("a\\nb", "ab") is not None
    assert validate_translation("a\\nb", "أ\\nب") is None


def test_empty_translation():
    assert validate_translation("Hello", "") is not None
    assert validate_translation("Hello", "   ") is not None


def test_escape_android_quotes():
    assert escape_android_quotes("don't") == "don\\'t"
    assert escape_android_quotes("don\\'t") == "don\\'t"
    assert escape_android_quotes('say "hi"') == 'say \\"hi\\"'
