# 什么是模式注入

模式注入是一个手动提示块。

它**不是**像 lorebook 条目那样由关键词触发，也**不是**完整的 ST 预设结构。

当你需要可复用的模式时可以使用它，例如：
- 学习模式
- 翻译模式
- 代码审查模式
- 写作润色模式

## 快速开始

1. 创建一个注入。
2. 给它起一个能描述该模式的名称。
3. 选择一个靠近系统提示的位置。
4. 将实际指令放入内容中。
5. 如果多个注入可以同时激活，使用优先级来控制顺序。

## 示例

```text
Name:
Explain Simply

Position:
After system prompt

Priority:
100

Content:
Explain concepts in plain language, avoid jargon, and use one short example when helpful.
```

## 如何理解位置

- **Before system prompt**：约束力最强，通常用于高层级行为。
- **After system prompt**：对大多数额外指令来说是更安全的默认选择。

> 除非你明确知道自己需要更强的覆盖效果，否则请从 **After system prompt** 开始。
