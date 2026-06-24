<div align="center">
  <img src="docs/icon.png" alt="应用图标" width="100" />
  <h1>Poci</h1>

一款原生 Android 大语言模型聊天客户端，支持在不同的服务商之间切换进行对话 🤖💬☁️

简体中文 | [繁體中文](README_ZH_TW.md) | [English](README.md)
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="聊天界面" width="150" />
  <img src="docs/img/desktop.png" alt="模型选择器" width="450" />
</div>

## ✨ 功能特性

- 🎨 Material You 设计与 🌙 深色模式
- 🔄 多 AI 服务商支持：自定义 API / URL / 模型（兼容所有 OpenAI、Google、Anthropic 接口）
- 🖼️ 多模态输入支持（图片、文本文档、PDF、Docx）
- 🖥️ 网页访问，多平台使用
- 🛠️ MCP 支持
- 📝 Markdown 渲染（代码高亮、LaTeX 公式、表格、Mermaid）
- 🪾 消息分支
- 🔍 搜索能力（Exa、Tavily、智谱、LinkUp、Brave、Perplexity 等）
- 🧩 提示词变量（模型名称、时间等）
- 🤳 服务商二维码导出与导入
- 🤖 智能体自定义
- 🧠 类 ChatGPT 记忆功能
- 📝 AI 翻译
- 🌐 自定义 HTTP 请求头与请求体
- 💌 SillyTavern 角色卡导入

## 🔨 构建

本项目使用 [Android Studio](https://developer.android.com/studio) 开发。

技术栈：

- [Kotlin](https://kotlinlang.org/)（开发语言）
- [Koin](https://insert-koin.io/)（依赖注入）
- [Jetpack Compose](https://developer.android.com/jetpack/compose)（UI 框架）
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)（偏好设置存储）
- [Room](https://developer.android.com/training/data-storage/room)（数据库）
- [Coil](https://coil-kt.github.io/coil/)（图片加载）
- [Material You](https://m3.material.io/)（UI 设计）
- [Navigation Compose](https://developer.android.com/develop/ui/compose/navigation)（导航）
- [Okhttp](https://square.github.io/okhttp/)（HTTP 客户端）
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)（JSON 序列化）
- [compose-icons/lucide](https://composeicons.com/icon-libraries/lucide)（图标库）

> [!TIP]
> 构建应用需要在 `app` 目录下提供 `google-services.json` 文件；本地构建（不触发 Firebase）使用占位文件即可。

构建/测试命令、模块结构与架构概览，请参见 [CLAUDE.md](CLAUDE.md) / [AGENTS.md](AGENTS.md)。

## 📄 许可证

本项目采用 **GNU AGPL v3.0** 许可（针对商业用途或更大规模部署设有分段商业许可条款）——完整条款请见 [LICENSE](LICENSE)。

Poci 是一个基于开源 AGPL-3.0 项目的分支（fork）；上游的许可证与版权声明均保留在 [LICENSE](LICENSE) 中。若你重新分发本项目或将其作为网络服务运行，必须遵守 AGPL（包括提供对应的源代码）。
