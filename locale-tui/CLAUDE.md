# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

locale-tui is a terminal user interface (TUI) tool for managing Android string resource translations. It provides features for viewing, editing, and AI-powered translation of `strings.xml` files across multiple languages.

## Development Commands

```bash
# Run the application (requires uv package manager)
cd locale-tui
uv run python src/main.py

# Run with Textual dev mode for CSS hot-reloading
uv run textual run --dev src/main.py
```

## Architecture

### Core Components

- **app.py**: Main Textual application entry, mounts ModuleSelectScreen
- **config.py**: YAML configuration loader using dataclasses, loads from `config.yml` and `.env`
- **models/entry.py**: `TranslationEntry` dataclass holding key-value translations across languages

### Screens (screens/)

- **module_select.py**: Initial screen for selecting which Android module to manage
- **translation_table.py**: Main translation interface with DataTable, search, filters, and AI translation

### Services (services/)

- **xml_parser.py**: Android `strings.xml` read/write using lxml
- **translator.py**: OpenAI-based batch translation with async API
- **dead_entry_finder.py**: Scans source code to detect unreferenced string keys

### Key Data Flow

1. Config loaded from `config.yml` (modules, languages, OpenAI settings)
2. User selects module -> TranslationTableScreen loads all `strings.xml` files
3. Entries collected into `TranslationEntry` objects with translations keyed by language code
4. Dead entry detection scans source patterns for `R.string.xxx` references
5. AI translation batches missing entries through OpenAI API

## Configuration

**config.yml**: Defines modules (res paths, source patterns), languages, translation settings

**Environment variables** (`.env`):
- `OPENAI_API_KEY`: Required for AI translation
- `OPENAI_BASE_URL`: API endpoint (defaults to OpenAI)

## Key Technologies

- **Textual**: TUI framework with reactive widgets
- **lxml**: XML parsing for strings.xml files
- **OpenAI SDK**: Async translation API client
- **PyYAML/python-dotenv**: Configuration loading
