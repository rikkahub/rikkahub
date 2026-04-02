# 此應用程式中的 Prompt 系統

此頁面將各種 prompt 功能分開，方便管理。

## 何時使用各功能

- **SillyTavern Preset**：共用的 ST prompt 順序、執行階段 prompts、sampling，以及 ST regex 腳本。
- **Mode Injection**：可手動啟用的 prompt 區塊，當你想使用某種模式時使用，例如翻譯模式或程式設計模式。
- **Lorebook**：由關鍵字觸發的世界觀資訊，只有在找到相符的內容時才會出現。

## 快速判斷指南

```text
我想讓整個 app 使用同一套 ST preset 資料庫
-> SillyTavern Preset

我想要一個可重複使用的手動模式，例如「用最簡單的方式解釋所有內容」
-> Mode Injection

我希望只有在出現特定詞語時才顯示事實或設定
-> Lorebook
```

## 示範：三個相似需求，三種不同工具

### 案例 A
「一律使用我匯入的 ST prompt 順序與續寫行為」

使用 **SillyTavern Preset**。

### 案例 B
「當我切換到學習模式時，在前面加上一段教學指令」

使用 **Mode Injection**。

### 案例 C
「當聊天提到王室家族時，注入王國設定」

使用 **Lorebook**。

> 如果某條規則應根據內容自動觸發，它很可能是 lorebook 項目。如果你想手動選擇使用，它很可能是 mode injection。
