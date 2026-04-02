# 這個編輯器的用途

Regex 規則會在聊天流程中的特定節點改寫文字。

你可以用它來：
- 清理匯入的 SillyTavern 格式
- 在傳送給模型前，統一名稱或標點符號
- 僅在 UI 中隱藏干擾文字
- 針對 ST 風格的位置，例如使用者輸入、AI 輸出、世界資訊、斜線指令或推理

## 快速開始

1. 為規則取一個清楚的名稱。
2. 在 **Find Regex** 中寫入模式。
3. 在 **Replace String** 中寫入輸出內容。
4. 決定它應影響實際訊息、僅 UI，或僅提示詞。
5. 如果這個規則來自 ST，請設定 **ST placements**，讓套用時機符合原始腳本。

## 範例 1：統一暱稱變體

```text
Goal:
將 "Alicia" 和 "Ally" 轉成 "Alice"

Find Regex:
Alicia|Ally

Replace String:
Alice
```

## 範例 2：在寫回前清理擷取群組

```text
Goal:
只保留符合結果中的有用部分

Find Regex:
Name:\s*(.*)

Replace String:
$1

Trim captured strings:
[
]
```

## 重要欄位

- **Trim captured strings**：在將取代內容寫回前，先從擷取群組中移除多餘的包裝文字。
- **RAW / ESCAPED macro substitution**：在比對前，先在 regex 本身內解析 `{{char}}` 和 `{{user}}` 等巨集。
- **ST placements**：如果這裡不是空白，會覆蓋上方的一般範圍設定。
- **Run on edit**：編輯現有訊息時也會套用這個規則。

> 經驗法則：如果你是從 SillyTavern 複製 regex，請先設定 ST placements。如果你要建立通用的全 App 清理規則，請先從一般範圍開始。
