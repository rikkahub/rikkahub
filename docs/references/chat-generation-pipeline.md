# 消息生成链路文档

本文档描述从用户发送消息到 AI 回复完成的完整数据流，涉及的核心类与处理阶段。

## 核心类概览

| 类                          | 职责                          |
|----------------------------|-----------------------------|
| `ChatService`              | 入口与编排层，管理所有会话，对外暴露操作接口      |
| `ConversationSession`      | 单个会话的状态容器（引用计数、生成 Job、处理状态） |
| `GenerationHandler`        | 核心生成逻辑，驱动 Step 循环与工具调用      |
| `InputMessageTransformer`  | 发送给 API 前对消息列表的变换管道         |
| `OutputMessageTransformer` | 接收到流式 chunk 后对消息列表的变换管道     |

---

## 完整生成链路

```
用户发送消息
    │
    ▼
ChatService.sendMessage()
    ├── 取消上一个 Job（cancel + join）
    ├── finishInterruptedPendingTools()  // 补全上次被打断的 Tool 输出
    ├── preprocessUserInputParts()       // 对用户文本执行助手 regex 替换
    ├── 将 UIMessage(USER) 追加到 Conversation.messageNodes
    └── handleMessageComplete()
            │
            ▼
        GenerationHandler.generateText()   ← Flow<GenerationChunk>
            │  (最多 maxSteps=256 轮循环)
            │
            ├─ [若无待处理 Tool] generateInternal()
            │       ├── 构建 internalMessages
            │       │       ├── System message（系统提示 + 记忆 + tool.systemPrompt）
            │       │       ├── limitContext() 按 contextMessageSize 裁剪历史
            │       │       └── InputTransformers 管道
            │       ├── 构建 TextGenerationParams
            │       └── 调用 Provider
            │               ├── stream=true → providerImpl.streamText() 逐 chunk emit
            │               └── stream=false → providerImpl.generateText() 一次返回
            │
            ├─ 每次收到 chunk → OutputTransformers.transforms() (实时)
            │                  → OutputTransformers.visualTransforms() → emit GenerationChunk
            │
            ├─ 生成完毕 → OutputTransformers.visualTransforms()
            │           → OutputTransformers.onGenerationFinish()
            │           → 设置 message.finishedAt
            │
            ├─ 检查最新消息中是否有未执行 Tool
            │       ├── 无 Tool → break（生成结束）
            │       ├── 有 Tool 且需要审批 → 设为 ToolApprovalState.Pending
            │       │                        → emit → break（等待用户审批）
            │       └── 有 Tool 且已审批 → 执行工具（见下）
            │
            ├─ 工具执行
            │       ├── Denied  → 输出 {"error": "denied by user"}
            │       ├── Answered → 直接使用用户提供的答案
            │       └── Auto / Approved → toolDef.execute(args)
            │               ├── 若输出超 32KB 且有 shell 权限 → 截断并写入文件
            │               └── CancellationException 必须向上传播（不能吞掉）
            │
            └─ 将执行结果写回 messages → emit → 继续下一 Step
                    (Tool 结果内联在 ASSISTANT 消息的 parts 中，不创建 TOOL 角色消息)

    ▼
onCompletion（Flow 结束或取消）
    ├── cancelLiveUpdateNotification()
    ├── 对所有消息 finishReasoning()（兜底）
    └── 若 App 不在前台 → sendGenerationDoneNotification()

    ▼
onSuccess
    ├── saveConversation()
    ├── generateTitle()    （异步，使用 titleModel）
    └── generateSuggestion()（异步，使用 suggestionModel）
```

---

## 阶段一：用户消息预处理

**入口**：`ChatService.preprocessUserInputParts()`

对用户输入的 `UIMessagePart.Text` 执行 `Assistant.replaceRegexes(scope=USER)`，即助手配置中 `AffectScope.USER`
的正则替换规则，用于规范化或脱敏输入文本。非文本 Part（图片、文档等）不参与处理。

---

## 阶段二：InputMessage 变换管道

**时机**：`generateInternal()` 构建 `internalMessages` 后，发送给 API 前调用。

变换器按顺序执行（`fold`），每个变换器接收上一个的输出：

| 顺序 | 变换器                            | 说明                                       |
|----|--------------------------------|------------------------------------------|
| 1  | `TimeReminderTransformer`      | 在系统消息中注入当前时间/日期                          |
| 2  | `PromptInjectionTransformer`   | 注入 ModeInjection 和 Lorebook 触发的提示词       |
| 3  | `PlaceholderTransformer`       | 替换消息中的 `{{placeholder}}` 占位符             |
| 4  | `DocumentAsPromptTransformer`  | 将文档附件转换为文本内容注入消息                         |
| 5  | `OcrTransformer`               | 对图片 Part 执行 OCR，将识别结果附加为文本               |
| 6  | `TemplateTransformer`          | 用 Pebble 模板引擎渲染消息（可访问 time/date/role 变量） |
| 7  | `WorkspaceReminderTransformer` | 若对话关联 Workspace，注入工作区路径提示                |

`PromptInjectionTransformer` 支持四种注入位置：

- `BEFORE_SYSTEM_PROMPT` / `AFTER_SYSTEM_PROMPT` — 插入到系统消息前后
- `TOP_OF_CHAT` — 插入到第一条用户消息前
- `BOTTOM_OF_CHAT` — 插入到最后一条消息前
- `AT_DEPTH` — 从最新消息往前数第 N 条处插入

---

## 阶段三：OutputMessage 变换管道

分三个时机调用：

| 时机                  | 方法                     | 说明                             |
|---------------------|------------------------|--------------------------------|
| 流式 chunk 到达时（实时）    | `transforms()`         | 真实变换，用于内部消息存储                  |
| 流式 chunk 到达时（UI 展示） | `visualTransforms()`   | 视觉变换，不影响实际存储（如流式 think tag 转换） |
| 生成完全结束后             | `onGenerationFinish()` | 最终后处理，如 base64 图片落盘            |

当前 Output 变换器：

| 变换器                                 | transforms            | visualTransform               | onGenerationFinish |
|-------------------------------------|-----------------------|-------------------------------|--------------------|
| `ThinkTagTransformer`               | —                     | ✓（`<think>` → Reasoning Part） | ✓（最终提取）            |
| `Base64ImageToLocalFileTransformer` | —                     | —                             | ✓（base64 → 本地文件）   |
| `RegexOutputTransformer`            | ✓（助手 regex OUTPUT 替换） | —                             | —                  |

---

## 阶段四：工具系统

### 工具注册顺序

`handleMessageComplete()` 中按如下顺序构建工具列表：

1. **Search Tools**（`createSearchTools`）— 当 `settings.enableWebSearch = true` 时
2. **Local Tools**（`localTools.getTools(assistant.localTools)`）— 按助手配置启用：
  - `JavascriptEngine`：执行 JS 代码片段
  - `TimeInfo`：获取当前时间
  - `Clipboard`：读写剪贴板
  - `Tts`：文字转语音
  - `AskUser`：向用户提问（需审批）
  - `ScreenTime`：获取屏幕使用时间
3. **Conversation Tools**（`createConversationTools`）— `enableRecentChatsReference = true` 时，查询历史对话
4. **Workspace Tools**（`createWorkspaceToolsIfReady`）— Workspace Shell 就绪时注入，含 `workspace_shell`
5. **Skill Tools**（`createSkillTools`）— 助手启用的 Skill 列表
6. **MCP Tools** — 所有已连接 MCP 服务器的工具，命名格式 `mcp__{serverName}__{toolName}`
7. **Memory Tools**（`buildMemoryTools`，内置于 GenerationHandler）— `enableMemory = true` 时，支持记忆的增删改

### 工具审批状态机

```
Auto（默认）
    │ toolDef.needsApproval() == true
    ▼
Pending ──── 用户操作 ────► Approved → 执行工具
                       ──► Denied   → 返回拒绝错误
                       ──► Answered → 使用用户提供的文本作为结果
```

审批流程由 `ChatService.handleToolApproval()` 触发，更新状态后重新调用 `handleMessageComplete()`，`GenerationHandler` 检测到
`canResumeExecution` 的 Tool 后直接跳过本轮生成，进入工具执行阶段。

### 工具输出截断

当 Workspace Shell 工具可用且工具输出超过 **32KB** 时，输出被截断：

- 前 4KB 保留在消息中
- 完整输出写入 `filesDir/tool_outputs/{toolCallId}.txt`
- 消息中附带 shell 读取指令提示

---

## 阶段五：会话生命周期管理

`ConversationSession` 使用**原子引用计数**管理内存生命周期：

- `acquire()` / `release()` — UI 页面打开/关闭时调用
- `refCount == 0 && !isGenerating` — 触发 5 秒空闲超时后，`ChatService.removeSession()` 清理 session
- `setJob()` — 设置生成 Job，完成后自动置 null 并触发空闲检查

---

## 阶段六：后台通知

| 通知类型                 | 触发条件           | Channel                                    |
|----------------------|----------------|--------------------------------------------|
| Live Update（ongoing） | 生成过程中且 App 在后台 | `CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID` |
| 生成完成                 | 生成结束且 App 在后台  | `CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID`   |

Live Update 通知内容根据当前生成状态动态更新：

- 工具执行中 → 显示工具名与输入预览
- 推理中（Reasoning）→ 显示推理内容片段
- 写回复中 → 显示文本内容片段

---

## 关键文件路径

```
app/src/main/java/me/rerere/rikkahub/
├── service/
│   ├── ChatService.kt              # 编排入口
│   └── ConversationSession.kt      # 会话状态容器
└── data/ai/
    ├── GenerationHandler.kt        # 核心生成逻辑
    ├── transformers/
    │   ├── Transformer.kt          # 接口定义与扩展函数
    │   ├── PromptInjectionTransformer.kt
    │   ├── TemplateTransformer.kt
    │   ├── TimeReminderTransformer.kt
    │   ├── ThinkTagTransformer.kt
    │   ├── RegexOutputTransformer.kt
    │   ├── DocumentAsPromptTransformer.kt
    │   ├── OcrTransformer.kt
    │   ├── Base64ImageToLocalFileTransformer.kt
    │   ├── PlaceholderTransformer.kt
    │   └── WorkspaceReminderTransformer.kt
    └── tools/
        ├── SearchTools.kt
        ├── ConversationTools.kt
        ├── WorkspaceTools.kt
        ├── SkillsTools.kt
        ├── MemoryTools.kt
        └── local/
            └── LocalTools.kt       # 本地工具注册表
```
