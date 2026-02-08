# CLAUDE.md

This file provides guidance to AI agents when working with code in this repository.

## Project Overview

web-ui 是 RikkaHub 项目的嵌入式 Web 前端,基于 React Router 7 构建的全栈 React 应用。构建产物会被复制到 `../web/src/main/resources/static` 目录,由 Kotlin 后端的 Ktor 服务器提供服务。

## Technology Stack

- **React Router 7**: Full-stack React framework with SSR and file-based routing
- **React 19**: UI framework
- **TypeScript**: Type-safe development
- **Tailwind CSS v4**: Utility-first CSS framework
- **shadcn/ui**: Component library (New York style)
- **Zustand**: State management
- **ky**: HTTP client for API requests
- **Bun**: Package manager and build runtime

## Development Commands

```bash
# Development server with HMR (proxies /api to localhost:8080)
bun run dev

# Production build (builds React Router app + copies to ../web/src/main/resources/static)
bun run build

# Type checking
bun run typecheck

# Format code
bun run fmt

# Check formatting
bun run fmt:check

# Start production server
bun run start
```

## Architecture

### Directory Structure

```
app/
├── routes/           # Route components (React Router 7 file-based routing)
├── components/       # Reusable UI components
│   ├── ui/          # shadcn/ui components
│   ├── message/     # Message-related components (message-part, text-part, reasoning-part)
│   └── markdown.tsx # Markdown renderer with LaTeX, code highlighting, and citation support
├── services/        # API client (ky-based HTTP client with error handling)
├── types/           # TypeScript type definitions (aligned with Kotlin backend types)
├── hooks/           # Custom React hooks
├── lib/             # Utility functions
├── routes.ts        # Route configuration
├── root.tsx         # Root layout component
└── app.css          # Global styles (Tailwind)
```

### Key Concepts

- **Type Alignment**: TypeScript types in `app/types/` are split into modules and aligned with Kotlin types from the Android app:
  - `core.ts`: `MessageRole` → `ai/src/main/java/me/rerere/ai/core/MessageRole.kt`, `TokenUsage` → `ai/src/main/java/me/rerere/ai/core/Usage.kt`
  - `parts.ts`: `UIMessagePart` (union of `TextPart`, `ImagePart`, `VideoPart`, `AudioPart`, `DocumentPart`, `ReasoningPart`, `ToolPart`), `ToolApprovalState` → `ai/src/main/java/me/rerere/ai/ui/Message.kt`
  - `annotations.ts`: `UIMessageAnnotation` (`UrlCitationAnnotation`) → `ai/src/main/java/me/rerere/ai/ui/Message.kt`
  - `message.ts`: `UIMessage` → `ai/src/main/java/me/rerere/ai/ui/Message.kt`
  - `conversation.ts`: `MessageNode`, `Conversation` → `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`
  - `dto.ts`: `ConversationDto`, `ConversationListDto`, `MessageDto`, `MessageNodeDto` → `app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt`
  - `settings.ts`: `DisplaySetting`, `Settings` → `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`, `AssistantProfile`, `AssistantTag`, `AssistantAvatar`
  - `helpers.ts`: Utility functions (`getCurrentMessage`, `getCurrentMessages`, type guards like `isTextPart`, `isImagePart`, etc.)
  - `index.ts`: Re-exports all modules

- **Message Parts**: Messages are composed of typed parts (text, image, video, audio, document, reasoning, tool) that can be mixed in a single message. See `UIMessagePart` union type in `app/types/index.ts`.

- **Message Branching**: `MessageNode` containers enable conversation branching where each node can contain multiple alternative messages (selected by `selectIndex`).

- **API Client**: `app/services/api.ts` provides a typed HTTP client built on `ky` with automatic error handling. All API calls use the `/api` prefix (proxied to `http://localhost:8080` in development).

### Build Process

The build process has two stages:
1. `react-router build` - Builds the React Router app to `build/client` and `build/server`
2. `bun copy.ts` - Copies `build/client` static assets to `../web/src/main/resources/static` for the Kotlin backend to serve

### Component Guidelines

- Use shadcn/ui components from `~/components/ui/` for consistent UI
- Follow New York style variant (configured in `components.json`)
- Import Lucide icons: `import { IconName } from "lucide-react"`
- Use path aliases: `~/` resolves to `app/` directory

### Markdown Rendering

The custom Markdown component (`app/components/markdown.tsx`) provides:
- LaTeX math rendering (inline `\(...\)` and block `\[...\]` syntax, converted to `$...$` and `$$...$$`)
- GFM (GitHub Flavored Markdown) support
- Code syntax highlighting with copy button
- `<think>` tag processing (converted to blockquotes)
- Citation link handling (`[citation,domain](id)` format)
- Light/dark theme support

### Routing

Routes are configured in `app/routes.ts` using React Router 7's type-safe route config:
- `/` - Home page (`routes/home.tsx`)
- `/c/:id` - Conversation detail page (`routes/c.$id.tsx`)

The actual conversation UI is implemented in `routes/conversations.tsx` with sidebar navigation.

### State Management

- Use Zustand for global state management
- Local component state with `useState` for UI-specific state
- Server state fetching with React hooks and API client

## Development Notes

- Development server proxies `/api` requests to `http://localhost:8080` (configured in `vite.config.ts`)
- Hot Module Replacement (HMR) is enabled in development mode
- Build artifacts in `build/` are gitignored
- Always run type checking before committing: `bun run typecheck`

## Integration with Kotlin Backend

- Static assets are served from `../web/src/main/resources/static` by the Ktor server
- API endpoints are defined in the `web` module's Kotlin code
- Type definitions must be kept in sync with Kotlin data classes
- Conversation state (including `isGenerating` flag) is managed by the backend via SSE
