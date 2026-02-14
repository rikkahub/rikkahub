# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Architecture Overview

### Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, and PPTX files
- **highlight**: Code syntax highlighting implementation
- **search**: Search functionality SDK (Exa, Tavily, Zhipu)
- **tts**: Text-to-speech implementation for different providers
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)

### Key Technologies

- **Jetpack Compose**: Modern UI toolkit
- **Koin**: Dependency injection
- **Room**: Database ORM
- **DataStore**: Preferences storage
- **OkHttp**: HTTP client with SSE support
- **Navigation Compose**: App navigation
- **Kotlinx Serialization**: JSON handling

### Core Packages (app module)

- `data/`: Data layer with repositories, database entities, and API clients
- `ui/pages/`: Screen implementations and ViewModels
- `ui/components/`: Reusable UI components
- `di/`: Dependency injection modules
- `utils/`: Utility functions and extensions

### Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time, and pin status. Conversations can be truncated at a specific index and maintain chat suggestions. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT, SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables users to regenerate responses and switch between different conversation branches, creating a tree-like conversation structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Development Guidelines

### UI Development

- Follow Material Design 3 principles
- Use existing UI components from `ui/components/`
- Reference `SettingProviderPage.kt` for page layout patterns
- Use `FormItem` for consistent form layouts
- Implement proper state management with ViewModels
- Use `Lucide.XXX` for icons, and import `import com.composables.icons.lucide.XXX` for each icon
- Use `LocalToaster.current` for toast messages

### Internationalization

- String resources located in `app/src/main/res/values-*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- If the user explicitly requests localization, all languages should be supported.
- English(en) is the default language. Chinese(zh), Japanese(ja), and Traditional Chinese(zh-rTW), Korean(ko-rKR) are supported.

#### locale-tui Tool

The `locale-tui` tool provides CLI and TUI interfaces for managing string resources with AI-powered translation.

**Add Command Usage:**

```bash
# Add a new string resource with automatic translation
uv run --directory locale-tui src/main.py add <key> <value> [OPTIONS]

# Examples:
uv run --directory locale-tui src/main.py add hello_world "Hello, World!"           # Add and auto-translate
uv run --directory locale-tui src/main.py add greeting "Welcome" -m app             # Specify module
uv run --directory locale-tui src/main.py add test_key "Test" --skip-translate      # Skip translation
```

**Options:**
- `--module, -m`: Specify module name (defaults to first module in config)
- `--skip-translate`: Skip automatic translation, only add to source language

**Behavior:**
1. Adds key-value pair to source language `strings.xml` (values/strings.xml)
2. By default, automatically translates to all configured target languages using OpenAI API
3. Saves translations to respective language directories (values-zh, values-ja, etc.)
4. Displays translation progress and results for each language
5. The input value should only be English

**Set Command Usage:**

```bash
# Manually set a string value for a specific language
uv run --directory locale-tui src/main.py set <key> <value> [OPTIONS]

# Examples:
uv run --directory locale-tui src/main.py set hello_world "你好，世界！" -l values-zh      # Set Chinese translation
uv run --directory locale-tui src/main.py set greeting "Welcome" -l values              # Set source language
uv run --directory locale-tui src/main.py set test_key "テスト" -l values-ja -m app       # Set Japanese with module
```

**Options:**
- `--lang, -l`: Specify language code (e.g., values, values-zh, values-ja), defaults to source language (values)
- `--module, -m`: Specify module name (defaults to first module in config)

**Behavior:**
1. Manually sets a key-value pair for a specific language without auto-translation
2. Useful for correcting or overriding auto-translated values
3. Creates the language directory and file if they don't exist

**Other Commands:**
- `uv run --directory locale-tui src/main.py list-keys [-m module]`: List all string resource keys

See `locale-tui/CLAUDE.md` for detailed documentation.

### Database

- Room database with migration support
- Schema files in `app/schemas/`
- Use KSP for Room annotation processing
- Current database version tracked in `AppDatabase.kt`

### AI Provider Integration

- New providers go in `ai/src/main/java/me/rerere/ai/provider/providers/`
- Extend base `Provider` class
- Implement required API methods following existing patterns
- Support for streaming responses via SSE
