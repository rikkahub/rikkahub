"""Translation entry data model."""

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class TranslationEntry:
    """Single translation entry."""

    key: str  # string name
    translations: dict[str, Optional[str]] = field(default_factory=dict)
    # translations: {"values": "Hello", "values-zh": "你好", ...}
    is_dead: bool = False  # whether this is an unreferenced dead entry

    def get_translation(self, lang_code: str) -> Optional[str]:
        """Get translation for a specific language."""
        return self.translations.get(lang_code)

    def set_translation(self, lang_code: str, value: str) -> None:
        """Set translation for a specific language."""
        self.translations[lang_code] = value

    def has_missing_translations(self, lang_codes: list[str]) -> bool:
        """Check if there are missing translations."""
        source = self.translations.get("values")
        if not source:
            return False
        for code in lang_codes:
            if code != "values" and not self.translations.get(code):
                return True
        return False

    def get_missing_languages(self, lang_codes: list[str]) -> list[str]:
        """Get list of languages with missing translations."""
        missing = []
        for code in lang_codes:
            if code != "values" and not self.translations.get(code):
                missing.append(code)
        return missing
