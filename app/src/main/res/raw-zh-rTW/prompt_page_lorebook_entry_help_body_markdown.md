# 編輯單一 lorebook 項目

這裡是你定義**何時**觸發此項目，以及**注入**哪些內容的地方。

## 建議工作流程

1. 撰寫主要關鍵字。
2. 新增內容。
3. 選擇注入位置。
4. 在聊天中測試。
5. 最後再加入機率、群組、sticky、cooldown 或 recursion 旗標等進階控制項。

## 範例 1：簡單的關鍵字項目

```text
主要關鍵字：
lighthouse, harbor beacon

內容：
The Old Harbor lighthouse has been abandoned for 20 years, but locals still believe its lamp turns on before storms.
```

## 範例 2：讓廣泛觸發更安全

```text
主要關鍵字：
king

次要關鍵字：
vale, elira

選擇性邏輯：
任一次要關鍵字
```

這表示此項目不會只是因為有人在隨機語境中說了「king」就被觸發。

## 重要區段

- **觸發條件**：關鍵字、regex 模式、區分大小寫、完整詞彙比對、機率，以及額外觸發來源。
- **注入設定**：內容要放在哪裡，以及使用哪個角色。
- **進階相容性**：ST 風格的群組、延遲、sticky、cooldown、recursion，以及匯出中繼資料。

## 實用建議

- 讓內容保持事實性且精簡。
- 與其寫巨大篇幅的項目內容，不如優先使用精準的關鍵字。
- 只有在你確實需要類似 ST 的執行階段行為時，才使用進階相容性。

> 如果你不確定，請先不要動進階設定，先讓基本觸發正常運作。
