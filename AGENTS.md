# Repository Guidelines

This document is for contributors: it outlines the repository's module structure and development workflow so you can get productive quickly and keep collaboration consistent.

## Build, Test, and Development Commands

The app module is built across two axes: a `distribution` flavor dimension (`sideload` | `play`) and the build types
`debug` / `release` / `baseline`. **Use the `sideloadDebug` variant for local development** — `sideload` is the
full-feature flavor (the `play` flavor restricts the workspace shell/terminal surface; see Product flavors below).

```bash
# Compile the app (fast feedback, no tests)
./gradlew :app:compileSideloadDebugKotlin --rerun-tasks -x :web:buildWebUi

# App unit tests (flavored variant) — optionally narrowed to a class/package/method
./gradlew :app:testSideloadDebugUnitTest --rerun-tasks -x :web:buildWebUi
./gradlew :app:testSideloadDebugUnitTest --tests "me.rerere.rikkahub.data.ai.shellrun.*" --rerun-tasks -x :web:buildWebUi

# Library-module unit tests have NO flavor — use the plain debug variant
./gradlew :ai:testDebugUnitTest :ai-runtime:testDebugUnitTest :workspace:testDebugUnitTest --rerun-tasks

# Lint — the authoritative gate. Pre-existing issues are filtered by app/lint-baseline.xml; only NEW issues fail.
./gradlew :app:lintSideloadDebug --rerun-tasks -x :web:buildWebUi

# Instrumented tests on a device/emulator
./gradlew :app:connectedSideloadDebugAndroidTest
```

Notes that save real time:
- **Always pass `-x :web:buildWebUi`** in JVM/dev loops. The `:web` module's `buildWebUi` task shells out to
  `pnpm run build` inside `web-ui/` (the React frontend embedded by the Ktor server) and requires Node + pnpm on PATH —
  it is only needed when you actually change the embedded web UI.
- **`--rerun-tasks`** — Gradle's incremental cache can report stale green; force a real run when verifying a change.
- **Lint catches what a module compile misses.** A call available on the compile JDK can still be a `NewApi` error at
  `minSdk = 26`; module-targeted `compileSideloadDebugKotlin` passes while `:app:lintSideloadDebug` rejects it. Run a
  full lint before declaring a change done.
- **Prerequisite:** the build applies the Google Services plugin and expects `app/google-services.json`; a placeholder
  file is sufficient for local builds that don't exercise Firebase.

## Coding Style & Naming Conventions

Formatting is unified via `.editorconfig`:

- Kotlin / Gradle scripts: 4-space indent, max line length 120.
- XML / JSON: 2-space indent.
- Markdown / YAML: 2-space indent, trailing whitespace allowed (used for alignment).

Naming conventions: modules are lowercase directories (e.g. `ai/`, `speech/`); Kotlin classes use PascalCase; test
classes end with `*Test`.

## Testing Guidelines

Tests are primarily JUnit / AndroidX Test. There is no enforced coverage threshold, but new logic should ship with
new/updated tests. Suggested file naming:

- Unit tests: `FooTest.kt`
- Instrumented tests: `FooInstrumentedTest.kt` or `*Test.kt`

## Module Structure

- **app**: Main application module with UI, ViewModels, services, Room database, DI wiring, and the app-specific
  message transformers. The composition root that wires every other module together.
- **ai**: Provider SDK abstraction — the wire layer. `provider/` (OpenAI, Google, Anthropic implementations),
  `registry/`, `core/`, and `ui/` (the platform-agnostic `UIMessage`/streaming model). Knows nothing about agents.
- **ai-runtime**: The agent/turn runtime that sits on top of `ai`. Owns the agent loop and its subsystems:
  `task/` (the agent-loop coordinator + budgets), `subagent/`, `hooks/`, `transformers/` (transformer contracts),
  `mcp/` (MCP config/contracts), `memory/` + `knowledge/` (RAG), `schedule/`, `board/`, and `contract/`. If a change is
  about *how a turn runs* (tools, hooks, budgets, scheduling, subagents) it belongs here, not in `ai`.
- **automation**: On-device UI automation surface (e.g. `ui_observe`/act) with package-scope gating and a kill switch.
- **workspace**: Sandboxed filesystem + shell execution surface (background shell runs, tail scoping, path/symlink
  containment) used by agent tooling.
- **common**: Common utilities and extensions (incl. untrusted-content framing, JSON helpers).
- **document**: Document parsing for PDF, DOCX, PPTX, and EPUB files.
- **highlight**: Code syntax highlighting implementation.
- **material3**: Material color utility extensions used by the app UI.
- **search**: Search SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others); maintains its
  own `values*/strings.xml`.
- **speech**: TTS and ASR implementations.
- **web**: Embedded Ktor web server that hosts the static frontend built from the `web-ui/` React project.

Non-Gradle directories: **web-ui/** (the React frontend, built by `:web:buildWebUi` via pnpm) and **locale-tui/** (a TUI
helper for managing string-resource translations; see the `locale-tui-localization` skill).

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Product flavors (security boundary)

The `sideload` and `play` flavors are not just store metadata — they gate the powerful on-device surfaces. The workspace
shell/terminal and its tool-exposure differ by flavor via flavor-specific source sets in `app/src/{sideload,play}/`
(e.g. `WorkspaceToolsGate.kt`, `WorkspaceControlsGate.kt`, `WorkspaceTerminalPage.kt`): `sideload` exposes the full
shell/terminal; `play` restricts it. When touching workspace/automation tooling, check BOTH flavor variants — a change
in only one source set silently diverges the two builds.

## Agent runtime & durable state

A turn is more than a single request. The `ai-runtime` task layer drives an agent loop (tool calls, hooks, per-step
context fit, wall-time/token budgets); **subagent and scheduled turns run through the same generation path and the same
`ChatMessageTransformers` pipeline as a normal chat turn** — fixes to turn behavior must hold for all three. Async work
that must survive process death is persisted in Room and replayed at cold start, not held in memory: the
`agent_events` queue (durable async-injection of e.g. background-shell completions into a conversation at an idle
turn-end seam) and the `shell_runs` store (background shell lifecycle). When changing terminal/completion ordering,
preserve the invariant that a row's terminal state and its completion event are made durable atomically (one
transaction), or the cold-start replay paths will not re-emit a lost completion.

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
