"""Validation helpers for translated Android string values."""

import re

# Android/Java format specifiers, e.g. %s, %d, %1$s, %.2f, %%
_PLACEHOLDER = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]")
_UNESCAPED_QUOTE = re.compile(r"(?<!\\)(['\"])")


def extract_placeholders(value: str) -> list[str]:
    """Return format specifiers in value, sorted so order changes are allowed."""
    return sorted(m.group(0) for m in _PLACEHOLDER.finditer(value))


def escape_android_quotes(value: str) -> str:
    """Escape bare ' and " which aapt rejects or silently drops."""
    return _UNESCAPED_QUOTE.sub(r"\\\1", value)


def validate_translation(source: str, translated: str) -> str | None:
    """Return an error message if translated is not a safe replacement for source."""
    if not translated or not translated.strip():
        return "empty translation"
    src_ph = extract_placeholders(source)
    dst_ph = extract_placeholders(translated)
    if src_ph != dst_ph:
        return f"placeholder mismatch: {src_ph} vs {dst_ph}"
    if source.count("\\n") != translated.count("\\n"):
        return "line break count mismatch"
    return None
