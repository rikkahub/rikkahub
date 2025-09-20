# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Architecture Overview

### Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **highlight**: Code syntax highlighting implementation
- **search**: Search functionality SDK (Exa, Tavily, Zhipu)
- **tts**: Text-to-speech implementation for different providers
- **common**: Common utilities and extensions

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

### Prerequisites

- Android Studio with Kotlin support
- Requires `google-services.json` in `app/` folder for Firebase features
- Signing keys in `local.properties` for release builds

### Key Files to Reference

- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt`: UI patterns
- `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`: Data storage
- `ai/src/main/java/me/rerere/ai/provider/providers/OpenAIProvider.kt`: AI provider implementation

## Build Configuration

### Gradle Configuration

- Multi-module project with version catalogs
- Supports ABI splits for arm64-v8a and x86_64
- Uses KSP for annotation processing

### Target SDK

- Compile SDK: 36
- Target SDK: 36
- Min SDK: 26
- JVM Target: 11
