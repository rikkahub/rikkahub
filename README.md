<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/icon-dark.png" />
    <img src="docs/icon.png" alt="Poci" width="100" />
  </picture>
  <h1>Poci</h1>

A native Android LLM chat client that supports switching between different providers for
conversations 🤖💬☁️

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md) | English
</div>

## ✨ Features

- 🎨 Material You Design and 🌙 Dark mode
- 🔄 Multiple AI Provider Support: custom API / URL / models (all OpenAI, Google, Anthropic compatible api)
- 🖼️ Multimodal input support (Image, Text Documentation, PDF, Docx)
- 🖥️ Web access for multi-platform use
- 🛠️ MCP support
- 📝 Markdown Rendering (with code highlighting, Latex formulas, tables, Mermaid)
- 🪾 Message Branching
- 🔍 Search capabilities (Exa, Tavily, Zhipu, LinkUp, Brave, Perplexity, etc.)
- 🧩 Prompt variables (model name, time, etc.)
- 🤳 QR code export and import for providers
- 🤖 Agent customization
- 🧠 ChatGPT-like memory feature
- 📝 AI Translation
- 🌐 Custom HTTP request headers and request bodies
- 💌 Silly Tavern character card import

## 🔨 Building

This project is developed using [Android Studio](https://developer.android.com/studio).

Technology stack:

- [Kotlin](https://kotlinlang.org/) (Development language)
- [Koin](https://insert-koin.io/) (Dependency Injection)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI framework)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) (Preference data
  storage)
- [Room](https://developer.android.com/training/data-storage/room) (Database)
- [Coil](https://coil-kt.github.io/coil/) (Image loading)
- [Material You](https://m3.material.io/) (UI design)
- [Navigation Compose](https://developer.android.com/develop/ui/compose/navigation) (Navigation)
- [Okhttp](https://square.github.io/okhttp/) (HTTP client)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (JSON serialization)
- [compose-icons/lucide](https://composeicons.com/icon-libraries/lucide) (Icon library)

> [!TIP]
> You need a `google-services.json` file in the `app` folder to build the app. A placeholder file is
> sufficient for local builds that do not exercise Firebase.

See [CLAUDE.md](CLAUDE.md) / [AGENTS.md](AGENTS.md) for the build/test commands, module layout, and
architecture overview.

## 🙏 Credits

Poci is a fork of [**RikkaHub**](https://github.com/rikkahub/rikkahub) by the upstream RikkaHub authors —
the original project this is built on. All upstream copyright and license notices are preserved in
[LICENSE](LICENSE); the app icon and Poci branding in this fork are its own.

## 📄 License

This project is licensed under the **GNU AGPL v3.0** (with a segmented commercial-license clause for
commercial use or larger deployments) — see [LICENSE](LICENSE) for the full terms.

As a fork of an AGPL-3.0 project, the upstream license and copyright notices are preserved unchanged in
[LICENSE](LICENSE). If you redistribute or run this as a network service, you must comply with the AGPL
(including offering the corresponding source).
