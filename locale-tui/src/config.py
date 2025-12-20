"""Configuration loader for locale-tui."""

import os
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional

import yaml
from dotenv import load_dotenv


@dataclass
class LanguageConfig:
    """Language configuration."""
    code: str
    name: str
    is_source: bool = False


@dataclass
class ModuleConfig:
    """Module configuration."""
    name: str
    res_path: str
    source_patterns: list[str] = field(default_factory=list)


@dataclass
class Config:
    """Application configuration."""
    # OpenAI configuration
    openai_api_key: str
    openai_base_url: str

    # Project configuration
    project_root: Path
    modules: list[ModuleConfig]
    languages: list[LanguageConfig]

    # Translation configuration
    translation_model: str
    translation_prompt: str
    batch_size: int

    # Display configuration
    column_widths: dict[str, int]
    page_size: int

    @classmethod
    def load(cls, config_path: Path) -> "Config":
        """Load configuration from file."""
        load_dotenv()

        with open(config_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)

        # Calculate project root path
        config_dir = config_path.parent
        project_root = (config_dir / data.get("project_root", "..")).resolve()

        # Parse modules
        modules = [
            ModuleConfig(
                name=m["name"],
                res_path=m["res_path"],
                source_patterns=m.get("source_patterns", []),
            )
            for m in data.get("modules", [])
        ]

        # Parse languages
        languages = [
            LanguageConfig(
                code=lang["code"],
                name=lang["name"],
                is_source=lang.get("is_source", False),
            )
            for lang in data.get("languages", [])
        ]

        # Translation configuration
        trans_config = data.get("translation", {})

        # Display configuration
        display_config = data.get("display", {})

        return cls(
            openai_api_key=os.getenv("OPENAI_API_KEY", ""),
            openai_base_url=os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1"),
            project_root=project_root,
            modules=modules,
            languages=languages,
            translation_model=trans_config.get("model", "gpt-4o-mini"),
            translation_prompt=trans_config.get("prompt_template", ""),
            batch_size=trans_config.get("batch_size", 10),
            column_widths=display_config.get(
                "column_widths", {"key": 30, "translation": 25}
            ),
            page_size=display_config.get("page_size", 50),
        )

    def get_language_name(self, code: str) -> str:
        """Get language display name."""
        for lang in self.languages:
            if lang.code == code:
                return lang.name
        return code

    def get_language_codes(self) -> list[str]:
        """Get all language codes."""
        return [lang.code for lang in self.languages]

    def get_source_language(self) -> Optional[LanguageConfig]:
        """Get source language configuration."""
        for lang in self.languages:
            if lang.is_source:
                return lang
        return None
