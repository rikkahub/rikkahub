<div align="center">
  <img src="docs/icon.png" alt="應用圖示" width="100" />
  <h1>Poci</h1>

一款原生 Android 大型語言模型聊天客戶端，支援在不同的服務供應商之間切換進行對話 🤖💬☁️

[简体中文](README_ZH_CN.md) | 繁體中文 | [English](README.md)
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="聊天介面" width="150" />
  <img src="docs/img/desktop.png" alt="模型選擇器" width="450" />
</div>

## ✨ 功能特性

- 🎨 Material You 設計與 🌙 深色模式
- 🔄 多 AI 服務供應商支援：自訂 API / URL / 模型（相容所有 OpenAI、Google、Anthropic 介面）
- 🖼️ 多模態輸入支援（圖片、文字文件、PDF、Docx）
- 🖥️ 網頁存取，多平台使用
- 🛠️ MCP 支援
- 📝 Markdown 渲染（程式碼高亮、LaTeX 公式、表格、Mermaid）
- 🪾 訊息分支
- 🔍 搜尋能力（Exa、Tavily、智譜、LinkUp、Brave、Perplexity 等）
- 🧩 提示詞變數（模型名稱、時間等）
- 🤳 服務供應商 QR Code 匯出與匯入
- 🤖 智慧體自訂
- 🧠 類 ChatGPT 記憶功能
- 📝 AI 翻譯
- 🌐 自訂 HTTP 請求標頭與請求主體
- 💌 SillyTavern 角色卡匯入

## 🔨 建置

本專案使用 [Android Studio](https://developer.android.com/studio) 開發。

技術棧：

- [Kotlin](https://kotlinlang.org/)（開發語言）
- [Koin](https://insert-koin.io/)（相依性注入）
- [Jetpack Compose](https://developer.android.com/jetpack/compose)（UI 框架）
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)（偏好設定儲存）
- [Room](https://developer.android.com/training/data-storage/room)（資料庫）
- [Coil](https://coil-kt.github.io/coil/)（圖片載入）
- [Material You](https://m3.material.io/)（UI 設計）
- [Navigation Compose](https://developer.android.com/develop/ui/compose/navigation)（導覽）
- [Okhttp](https://square.github.io/okhttp/)（HTTP 用戶端）
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)（JSON 序列化）
- [compose-icons/lucide](https://composeicons.com/icon-libraries/lucide)（圖示庫）

> [!TIP]
> 建置應用需要在 `app` 目錄下提供 `google-services.json` 檔案；本機建置（不觸發 Firebase）使用佔位檔案即可。

建置/測試指令、模組結構與架構概覽，請參見 [CLAUDE.md](CLAUDE.md) / [AGENTS.md](AGENTS.md)。

## 📄 授權條款

本專案採用 **GNU AGPL v3.0** 授權（針對商業用途或更大規模部署設有分段商業授權條款）——完整條款請見 [LICENSE](LICENSE)。

Poci 是一個基於開源 AGPL-3.0 專案的分支（fork）；上游的授權條款與版權聲明皆保留於 [LICENSE](LICENSE) 中。若你重新散布本專案或將其作為網路服務執行，必須遵守 AGPL（包括提供對應的原始碼）。
