# 此编辑器的作用

Regex 规则会在聊天管线的特定阶段重写文本。

你可以用它们来：
- 清理导入的 SillyTavern 格式
- 在发送给模型前统一名称或标点
- 仅在 UI 中隐藏杂乱文本
- 定位 ST 风格的作用位置，例如用户输入、AI 输出、世界信息、斜杠命令或推理

## 快速开始

1. 为规则取一个清晰的名称。
2. 在 **Find Regex** 中编写模式。
3. 在 **Replace String** 中编写输出内容。
4. 决定它应影响真实消息、仅影响 UI，还是仅影响提示词。
5. 如果此规则来自 ST，请设置 **ST placements**，使触发时机与原始脚本一致。

## 示例 1：统一昵称变体

```text
目标：
将 "Alicia" 和 "Ally" 变为 "Alice"

Find Regex:
Alicia|Ally

Replace String:
Alice
```

## 示例 2：回写前清理捕获组

```text
目标：
只保留匹配结果中有用的部分

Find Regex:
Name:\s*(.*)

Replace String:
$1

Trim captured strings:
[
]
```

## 重要字段

- **Trim captured strings**：在将替换结果回写前，先从捕获组中移除多余的包裹文本。
- **RAW / ESCAPED macro substitution**：在匹配前，先解析 regex 本身中的宏，例如 `{{char}}` 和 `{{user}}`。
- **ST placements**：如果此项非空，它会覆盖上面的通用作用范围。
- **Run on edit**：编辑现有消息时也应用此规则。

> 经验法则：如果你是从 SillyTavern 复制 regex，先设置 ST placements。如果你是在构建通用于整个应用的清理规则，就先从通用作用范围开始。
