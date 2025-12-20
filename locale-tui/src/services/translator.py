"""AI translation service using OpenAI SDK."""

import json
from typing import Optional, Callable, TYPE_CHECKING

from openai import AsyncOpenAI

if TYPE_CHECKING:
    from config import Config
    from models.entry import TranslationEntry


class TranslationError(Exception):
    """Translation error."""

    pass


class AITranslator:
    """AI translation service."""

    def __init__(self, config: "Config"):
        self.config = config
        self.client = AsyncOpenAI(
            api_key=config.openai_api_key, base_url=config.openai_base_url
        )

    async def translate_batch(
        self,
        entries: dict[str, str],  # {key: source_text}
        target_language: str,
    ) -> dict[str, str]:
        """Translate a batch of entries."""
        prompt = self.config.translation_prompt.format(
            target_language=target_language,
            source_strings=json.dumps(entries, ensure_ascii=False, indent=2),
        )

        try:
            response = await self.client.chat.completions.create(
                model=self.config.translation_model,
                messages=[{"role": "user", "content": prompt}],
                temperature=0.3,
            )

            content = response.choices[0].message.content
            if not content:
                raise TranslationError("Empty response from API")

            # Parse JSON response - handle potential markdown code blocks
            content = content.strip()
            if content.startswith("```"):
                # Remove markdown code block
                lines = content.split("\n")
                content = "\n".join(lines[1:-1])

            result = json.loads(content)
            return result

        except json.JSONDecodeError as e:
            raise TranslationError(f"Failed to parse response: {e}")
        except Exception as e:
            raise TranslationError(f"Translation failed: {e}")

    async def translate_all_missing(
        self,
        entries: list["TranslationEntry"],
        target_languages: list[str],
        progress_callback: Optional[Callable[[str, int, int, str], None]] = None,
    ) -> int:
        """Translate all missing entries."""
        total_translated = 0
        batch_size = self.config.batch_size

        for lang_code in target_languages:
            if lang_code == "values":
                continue

            lang_name = self.config.get_language_name(lang_code)

            # Collect entries missing for this language
            missing_entries = {}
            for entry in entries:
                source = entry.get_translation("values")
                if source and not entry.get_translation(lang_code):
                    missing_entries[entry.key] = source

            if not missing_entries:
                continue

            # Translate in batches
            keys = list(missing_entries.keys())
            for i in range(0, len(keys), batch_size):
                batch_keys = keys[i : i + batch_size]
                batch = {k: missing_entries[k] for k in batch_keys}

                if progress_callback:
                    progress_callback(
                        lang_code,
                        i,
                        len(keys),
                        f"Translating to {lang_name}... ({i}/{len(keys)})",
                    )

                try:
                    translations = await self.translate_batch(batch, lang_name)

                    # Update entries
                    for entry in entries:
                        if entry.key in translations:
                            entry.set_translation(lang_code, translations[entry.key])
                            total_translated += 1
                except TranslationError:
                    # Continue with other batches on error
                    continue

        return total_translated
