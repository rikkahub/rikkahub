# Agent Runtime 设计文档（对齐 Claude Code / Codex，功能全保留）

> 状态：**里程碑已交付（CHAT 兼容 + Plan/Agent + Explore Subagent + Hooks/Compact）**  
> 日期：2026-07-26  
> 范围：在 **不删除、不降级** RikkaHub 现有能力的前提下，对自研 Agent 做结构化演进  
> 相关文档：[chat-generation-pipeline.md](./chat-generation-pipeline.md)  
>  
> 实现入口：`data/ai/agent/`，门面 `GenerationHandler`，编排 `ChatService` + `ToolRegistry`  
> UI：聊天顶栏 Agent 模式 Chip（助手绑定 Workspace 时可见）+ FilesPicker 模式按钮 + Explore 轨迹面板

---

## 1. 背景与动机

RikkaHub 当前已具备完整的聊天 + 工具 Agent 能力：

- 自研多步 tool loop（`GenerationHandler`，默认最多 256 步）
- 多供应商协议（OpenAI / Claude / Google 兼容）
- 本地工具、搜索、MCP、Skills、Workspace（proot）、记忆
- 助手体系、Lorebook / 模式注入、正则、模板、消息分支、Web 多端

产品形态是 **移动端 / Web 的通用 AI 客户端**，不是终端 coding CLI。

业界 coding agent 中，**Claude Code（CC）** 与 **OpenAI Codex** 的产品层设计更贴近「可干活的 Agent」，可借鉴之处包括：

| 能力 | Claude Code | Codex | 对 RikkaHub 的价值 |
|------|-------------|-------|-------------------|
| 权限 / 审批策略 | Permissions / Auto / Hooks | sandbox + approval policy 写进 prompt | 升级现有 `ToolApproval` |
| 计划再执行 | Plan Mode（先只读） | 强约束 + 先分析后改 | Workspace 编码场景 |
| 项目指令文件 | `CLAUDE.md` | `AGENTS.md` 级联 | Workspace 内约定 |
| Skills | `SKILL.md` 生态 | 同样支持 Skills | 强化现有 Skill 体系 |
| 上下文压缩 | 会话管理 / compact | 上下文与会话策略 | 接口化现有压缩 |
| Subagent | 探索 / 通用子代理 | — | 后期可选 |
| 沙箱边界 | sandbox / hooks | OS 级 sandbox + 边界说明 | 强化 proot Workspace 表述 |

**不采用** 嵌入 CC / Codex / Pi 二进制运行时的方案（栈不匹配：Kotlin/Android vs Node/Rust CLI）。

---

## 2. 目标

### 2.1 要做

1. **架构向 CC/Codex 对齐**：小而清晰的 agent loop + 可组合策略/扩展点。  
2. **能力向 RikkaHub 对齐**：现有聊天与 Agent 功能 **默认行为不变**。  
3. **增量增强**：Plan Mode、项目指令、权限策略、Hooks 等均为 **opt-in 或默认兼容**。  
4. **可测试、可演进**：工具注册、审批、提示拼装可单测；文档与实现同步。

### 2.2 明确不做（Non-goals）

| 不做 | 原因 |
|------|------|
| 嵌入 Claude Code / Codex / Pi 进程 | 运行时、包体积、生命周期、协议不适配 Android 主路径 |
| 删除或默认关闭现有工具/MCP/记忆/角色能力 | 违反「功能全保留」 |
| 替换 `UIMessage` / Room schema / Provider 栈 | 成本高、收益低、破坏兼容 |
| 默认强制 Plan Mode | 破坏普通聊天与角色扮演体验 |
| 把产品改成「只能写代码的 CLI」 | 与 RikkaHub 定位冲突 |

---

## 3. 硬约束：功能全保留

以下能力在默认配置下 **语义与行为必须与改造前一致**（允许内部实现搬家，不允许默认策略变更）：

### 3.1 产品能力清单

- 多供应商、自定义 API / Header / Body  
- 多模态输入（图 / 文 / PDF / Docx 等）与 OCR  
- 消息分支、会话管理、Web 多端  
- 助手：系统提示、温度、推理级别、预设消息、正则、模板  
- 记忆（助手隔离 / 全局）  
- 联网搜索（Exa / Tavily / 等）  
- 本地工具（JS、时间、剪贴板、TTS、AskUser、屏幕时间、日历等）  
- 最近对话检索工具  
- Workspace（proot）工具与提醒  
- Skills（`SKILL.md`）  
- MCP 工具（`mcp__{server}__{tool}`）  
- Lorebook / 模式注入 / 提示词注入位置  
- 工具审批状态机（Auto / Pending / Approved / Denied / Answered）  
- 工具输出 32KB 截断（Workspace shell 可用时）  
- 标题 / 建议二次生成、翻译等旁路生成  
- 后台通知（Live Update / 完成通知）

### 3.2 不可破坏的运行时契约

摘自当前实现与 [chat-generation-pipeline.md](./chat-generation-pipeline.md)，改造后必须保持：

| 契约 | 现状 |
|------|------|
| 最大步数 | `maxSteps = 256`（可后续做成可配置，**默认仍为 256**） |
| Tool 结果落点 | 内联在 ASSISTANT 消息的 `UIMessagePart.Tool` 中，**不**单独创建 TOOL 角色消息 |
| 工具注册顺序 | 搜索 → 本地 → 对话 → Workspace → Skill → MCP；Memory 在 Handler 内与传入 tools 合并 |
| 审批 | `needsApproval == true` 且状态为 Auto 时 → Pending，等待用户 |
| 取消 | `CancellationException` 必须向上传播 |
| 输出截断 | 超 32KB 且存在 `workspace_shell` 时截断并落盘 |
| Input transformers 顺序 | TimeReminder → PromptInjection → Placeholder → DocumentAsPrompt → Ocr → Template → WorkspaceReminder |
| 对外入口 | `ChatService` 仍是会话编排入口；UI / Web API 不因内部重构而改语义 |

### 3.3 兼容原则（Compatibility Rules）

1. **默认 Profile = FULL**：等于今天的全功能行为。  
2. **新模式 / 新策略默认关闭或等价于现状**。  
3. **数据结构优先兼容**：`Tool`、`UIMessage`、`ToolApprovalState`、`Assistant` 字段以扩展为主，避免破坏性改名。  
4. **分 Phase 合入**，每 Phase 可独立回滚。  
5. **有行为 diff 必须有显式开关或迁移说明**。

---

## 4. 设计原则（从 CC / Codex 提炼）

### 4.1 Brain / Hands / Session（概念分层）

借鉴 Anthropic Managed Agents / Claude Code 的「脑–手–会话」拆分：

| 层 | 含义 | RikkaHub 对应 |
|----|------|----------------|
| **Brain** | 模型 + 提示 + 工具路由 + 循环决策 | `AgentLoop` + `PromptAssembler` + Provider |
| **Hands** | 工具执行环境 | Local / MCP / Workspace proot / Search… |
| **Session** | 可恢复的对话与工具状态 | `Conversation` + `ConversationSession` + Room |

改造重点在 Brain 的结构化；Hands 与 Session 保持并增强边界说明，不推倒重来。

### 4.2 策略写进模型可见上下文（学 Codex）

Codex 将 sandbox / approval 以 developer 消息说明，使模型知道边界。

RikkaHub 应：

- 将 **PermissionPolicy 摘要** 注入 system（可关）  
- 将 **Workspace 沙箱边界** 结构化（在现有 `WorkspaceReminderTransformer` 上增强）  
- **MCP 工具不由 Workspace sandbox 托管**（与 Codex「MCP 自管安全」一致），在文档与提示中写清

### 4.3 计划与执行分离（学 Claude Code Plan Mode）

非平凡的 Workspace 任务：

1. **Plan**：只读探索 + 产出计划，写/shell 禁用或强制审批  
2. 用户确认  
3. **Agent**：开放执行工具

**默认对话模式保持现在的直接执行**，Plan 仅 opt-in。

### 4.4 项目级指令文件（学 Codex AGENTS.md / CC CLAUDE.md）

Workspace 内支持级联加载项目说明文件，作为对 system prompt 的补充，**不覆盖** 用户/助手更高优先级指令。

### 4.5 扩展点优于硬编码（学 CC Hooks / Skills）

- 工具来源 → `ToolProvider`  
- 执行前后 → `AgentHook`  
- 压缩 → `CompactPolicy`  
- Skills 保持 `SKILL.md` 开放格式

---

## 5. 目标架构

### 5.1 模块图

```
ChatService                          # 编排入口（对外 API 保持）
    │
    ▼
AgentRuntime                         # 新门面（兼容现 generateText 语义）
    ├── AgentLoop                    # step 循环（自 GenerationHandler 抽出）
    ├── ToolRegistry                 # 解析并合并 ToolProvider
    │     ├── SearchToolProvider
    │     ├── LocalToolProvider
    │     ├── ConversationToolProvider
    │     ├── WorkspaceToolProvider
    │     ├── SkillToolProvider
    │     ├── McpToolProvider
    │     └── MemoryToolProvider
    ├── PermissionPolicy             # 类别 + 策略（默认映射现状）
    ├── ModeController               # chat | plan | agent（默认 chat）
    ├── ProjectDocsLoader            # AGENTS.md / CLAUDE.md 等（Workspace）
    ├── PromptAssembler              # system + 记忆 + 策略说明 + transformers
    ├── CompactPolicy                # 上下文压缩（默认 = 现状）
    └── AgentHooks                   # before/after tool（可选）
    │
    ▼
ProviderManager + UIMessage + Room   # 不变
```

### 5.2 建议包结构

```
app/src/main/java/me/rerere/rikkahub/data/ai/
├── GenerationHandler.kt              # 保留为薄门面或逐步委托
├── agent/                            # 新增
│   ├── AgentRuntime.kt
│   ├── AgentLoop.kt
│   ├── AgentMode.kt                  # CHAT | PLAN | AGENT
│   ├── AgentEvent.kt                 # 可选可观测事件
│   ├── permission/
│   │   ├── PermissionPolicy.kt
│   │   ├── ToolCategory.kt
│   │   └── PermissionPrompt.kt
│   ├── tools/
│   │   ├── ToolRegistry.kt
│   │   ├── ToolProvider.kt
│   │   ├── ToolResolveContext.kt
│   │   └── providers/
│   │         ├── SearchToolProvider.kt
│   │         ├── LocalToolProvider.kt
│   │         ├── ConversationToolProvider.kt
│   │         ├── WorkspaceToolProvider.kt
│   │         ├── SkillToolProvider.kt
│   │         ├── McpToolProvider.kt
│   │         └── MemoryToolProvider.kt
│   ├── prompt/
│   │   ├── PromptAssembler.kt
│   │   └── ProjectDocsLoader.kt
│   ├── compact/
│   │   └── CompactPolicy.kt
│   └── hooks/
│         └── AgentHook.kt
├── transformers/                     # 保持
└── tools/                            # 现有工具实现保留；Provider 调用它们
```

> 实施时允许渐进搬家：先委托，再删重复代码。不必一次搬完。

### 5.3 核心类型（草图）

#### AgentMode

```text
CHAT  — 默认；行为 = 今日 RikkaHub 全功能 tool loop
PLAN  — 只读（或写操作强制 Pending）；产出计划；不默认开启
AGENT — 执行模式；权限策略生效；可由 Plan 确认后进入
```

普通助手未绑定 Workspace 时，仅使用 `CHAT`（与现在一致）。

#### ToolProvider

```text
interface ToolProvider {
  val order: Int                          // 保证注册顺序契约
  fun isEnabled(ctx: ToolResolveContext): Boolean
  suspend fun provide(ctx: ToolResolveContext): List<Tool>
}
```

`ToolResolveContext` 至少包含：`settings`、`assistant`、`conversation`、`mode`、`workspaceCwd` 等。

#### PermissionPolicy

```text
enum ToolCategory {
  SEARCH, LOCAL_SAFE, LOCAL_SENSITIVE, CONVERSATION,
  WORKSPACE_READ, WORKSPACE_WRITE, WORKSPACE_SHELL,
  SKILL, MCP, MEMORY
}

enum ApprovalAction { AUTO, ASK }

data class PermissionPolicy(
  // 默认策略必须等价于当前 needsApproval 行为
  val byCategory: Map<ToolCategory, ApprovalAction>,
  // 可选：助手或全局覆盖
)
```

映射规则（Phase 2 必须写死对照表并单测）：

- 当前 `needsApproval == false` → `AUTO`  
- 当前 `needsApproval == true`（如 AskUser）→ `ASK`  
- Plan 模式下 `WORKSPACE_WRITE` / `WORKSPACE_SHELL` → 强制 `ASK` 或直接拒绝执行（实现二选一，需产品确认；推荐 **拒绝执行并提示切换模式** 更清晰）

#### ProjectDocsLoader

加载顺序建议（对齐 Codex 级联思想，适配 Workspace）：

1. Workspace 根目录：`AGENTS.md` / `CLAUDE.md` / `RIKKA.md`（文件名优先级可配置）  
2. 从 workspace 根到当前 `cwd` 路径上的同名文件（深层覆盖浅层冲突项，或简单拼接并标注来源）  
3. 总大小上限：默认 **32 KiB**（对齐 Codex 量级，可配置）  
4. 优先级：用户消息 / 助手 systemPrompt / 对话 customSystemPrompt **高于** 项目文档

无 Workspace 或文件不存在时：**不注入任何内容**（与现状一致）。

---

## 6. 与现有链路的关系

改造后的主路径应仍可映射到现有文档：

```
用户发送
  → ChatService.sendMessage / handleMessageComplete
  → 组装 ToolResolveContext + AgentMode + PermissionPolicy
  → AgentRuntime.run / GenerationHandler.generateText（兼容门面）
       → AgentLoop
            → PromptAssembler（含 ProjectDocs、权限摘要）
            → Provider 流式生成
            → 审批 / 执行 / Hooks / 截断
            → emit GenerationChunk.Messages
  → 落库 / 通知 / 标题与建议
```

**ChatService 对外方法、Web API、UI 订阅的 Flow 形状保持稳定。**

---

## 7. 分阶段实施计划

### Phase 0 — 契约与基线

- [x] 固化本文档中的「不可破坏契约」清单  
- [x] 为工具名集合 / 注册顺序 / 审批状态机增加或强化测试  
- [x] 在 `chat-generation-pipeline.md` 增加指向本文档的链接  

### Phase 1 — AgentLoop + ToolRegistry（纯重构）

- [x] 从 `GenerationHandler` 抽出 `AgentLoop`  
- [x] 引入 `ToolRegistry` + 全部现有来源的 `ToolProvider`  
- [x] `ChatService` 工具组装改为 Registry  
- [x] `GenerationHandler` 保留为兼容门面  

### Phase 2 — PermissionPolicy（学 CC/Codex，默认兼容）

- [x] 定义 `ToolCategory` 与默认映射表（= 现状）  
- [x] `needsApproval` 统一走 Policy（工具仍可声明更严）  
- [x] 可选：向 system 注入简短权限说明（Workspace + 非 CHAT 模式时）  

### Phase 3 — Plan Mode（学 CC，opt-in）

- [x] `AgentMode.PLAN` / `AGENT`  
- [x] Plan 下过滤写/shell 工具；执行时硬拒绝兜底  
- [x] UI：Workspace 就绪时 FilesPicker 可循环切换模式  
- [x] 模式落在 `Conversation.agentMode`（Room v25）  

### Phase 4 — 项目指令文件（学 Codex AGENTS.md）

- [x] `ProjectDocsLoader`  
- [x] `ProjectDocsTransformer` 接入生成链路  
- [x] 大小上限 32KiB、级联路径、`AGENTS.md`/`CLAUDE.md`/`RIKKA.md`  

### Phase 5 — Compact / Hooks / Subagent

- [x] `CompactPolicy` 接口 + `DefaultCompactPolicy`  
- [x] `AgentHook` / `CompositeAgentHook`（默认 NoOp）  
- [x] **Explore Subagent 已实现并接入主路径**
  - 工具名：`explore_subagent`（父会话注册；`isSubagentRun` 时不注册，防嵌套）
  - 运行器：`DefaultSubagentRunner` → 隔离消息 + `AgentLoop` + PLAN + 只读白名单
  - UI：`ExploreSubagentToolUI`
  - 进度：写入父会话 `processingStatus`

---

## 8. 功能保留对照表

| 现有能力 | 改造后归属 | 默认 |
|----------|------------|------|
| GenerationHandler loop | AgentLoop | 启用 |
| 搜索工具 | SearchToolProvider | 按助手 `enableWebSearch` |
| 本地工具 | LocalToolProvider | 按 `assistant.localTools` |
| 对话检索 | ConversationToolProvider | 按 `enableRecentChatsReference` |
| Workspace | WorkspaceToolProvider + Mode | shell 就绪时注入 |
| Skills | SkillToolProvider | 按 `enabledSkills` |
| MCP | McpToolProvider | 已连接服务器 |
| Memory | MemoryToolProvider | 按 `enableMemory` |
| Transformers 管道 | PromptAssembler 调用原 transformers | 顺序不变 |
| 审批 UI | PermissionPolicy + 原状态机 | 行为不变 |
| 角色卡 / Lorebook / 正则 | 原链路 | 不变 |
| Web / 分支 / 通知 | ChatService 等 | 不变 |

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 重构导致工具顺序变化 | 模型选工具行为漂移 | `order` 写死 + 快照测试 |
| 权限策略默认过严 | 用户感觉「坏了」 | 默认映射 = 现状；新策略 opt-in |
| Plan Mode 误触 | 聊天无法调写工具 | 仅 Workspace 暴露入口；默认 CHAT |
| AGENTS.md 挤占 context | 有效对话变短 | 32KiB 上限；可关；来源可折叠 |
| 大 PR 难审 | 回归不足 | 严格 Phase 拆分 |
| 文档与代码漂移 | 后续贡献者踩坑 | Phase 完成时同步更新 pipeline 文档 |

---

## 10. 测试策略

### 10.1 单元测试

- `ToolRegistry`：给定 Assistant/Settings 的 tool name 列表与顺序  
- `PermissionPolicy`：类别 → AUTO/ASK 映射；Plan 模式覆盖  
- `ProjectDocsLoader`：级联、上限、缺失文件  
- 审批状态机：Auto→Pending→Approved/Denied/Answered  

### 10.2 集成 / 手工回归清单

每 Phase 合并前至少覆盖：

1. 纯文本多轮聊天  
2. 流式 + 停止生成  
3. 需审批工具（AskUser）  
4. MCP 工具调用  
5. Skill 启用  
6. Workspace shell / 读写  
7. 记忆读写  
8. 搜索  
9. 消息分支与重新生成  
10. Web 端发送与流式（若本 Phase 触及服务层）

---

## 11. 决策记录（ADR 摘要）

| ID | 决策 | 理由 |
|----|------|------|
| ADR-1 | 不嵌入 CC/Codex/Pi 运行时 | 栈与产品形态不匹配 |
| ADR-2 | 学 CC/Codex 产品层，不学 Pi 极简砍工具 | RikkaHub 是全功能客户端 |
| ADR-3 | 默认 Profile/Mode = 今日行为 | 功能全保留 |
| ADR-4 | Tool 结果仍内联 ASSISTANT parts | 兼容现 UI/DB/Provider 适配 |
| ADR-5 | 项目文档优先级低于助手/用户指令 | 与 Codex 优先级模型一致 |
| ADR-6 | Plan Mode 默认关闭 | 保护聊天与角色场景 |

---

## 12. 已决议（本里程碑）

1. **Plan 写操作**：硬拒绝（工具不注册 + 执行期兜底错误「切换到 AGENT」）。  
2. **项目文档**：默认同时尝试 `AGENTS.md` / `CLAUDE.md` / `RIKKA.md`，级联拼接，32KiB 上限。  
3. **权限注入**：`AgentMode != CHAT` 时注入；CHAT 不注入。  
4. **Explore Subagent**：已实现并默认注册（父会话）；GENERAL 子代理 deferred。  
5. **UI**：顶栏 AssistChip（助手绑定 Workspace 时）+ FilesPicker 模式按钮；无独立 Agent 设置页。

---

## 13. 成功标准

1. **兼容**：默认设置下，用户感知不到「换了 Agent 框架」。  
2. **结构**：新人能根据本文档与 pipeline 文档定位 loop / 工具 / 权限 / 提示拼装。  
3. **增强**：Workspace 场景可选 Plan + AGENTS.md 后，编码类任务可控性接近 CC/Codex 的用法习惯（仍运行在 proot 内）。  
4. **可回滚**：任一 Phase 可独立 revert，不依赖后序 Phase。

---

## 14. 参考

### 内部

- [chat-generation-pipeline.md](./chat-generation-pipeline.md)  
- `GenerationHandler.kt`、`ChatService.kt`  
- `data/ai/tools/*`、`data/files/SkillManager.kt`  
- `workspace/*`（proot）

### 外部（设计参考，非依赖）

- OpenAI：Codex agent loop / sandbox / `AGENTS.md` 说明  
- Anthropic：Claude Code Plan Mode、Permissions、Skills、Subagents  
- [agents.md](https://agents.md/) 开放格式  
- Agent Skills（`SKILL.md`）生态（RikkaHub 已部分兼容）

---

## 15. Explore Subagent 使用说明

### 父 Agent 如何调用

模型在主会话中可调用工具：

```json
{
  "name": "explore_subagent",
  "arguments": {
    "task": "Find where workspace tools are registered and how Plan mode filters them",
    "max_steps": 12
  }
}
```

返回 JSON 字段：`success`、`summary`、`notes`、`steps_used`、`tools_used`、`error?`。

### 隔离语义

| 项 | 行为 |
|----|------|
| 消息 | 独立列表，不写回父会话 messageNodes |
| 模式 | PLAN（写/shell 不可用） |
| 工具白名单 | read / search / conversation / skill / time |
| 嵌套 | `isSubagentRun=true` 时不注册 `explore_subagent` |
| 进度 | 写入父会话 `processingStatus` |

### 关键类

- `DefaultSubagentRunner` — 执行隔离 loop，产出 `trace` 轨迹  
- `ExploreSubagentToolProvider` — 父会话工具入口（JSON 含 `trace` / `tools_used_list`）  
- `ExploreSubagentToolUI` — 聊天折叠摘要 + 底部面板「探索轨迹」  

### UI 观测面板

点击消息中的 `explore_subagent` 步骤可打开 BottomSheet，展示：

1. 状态（成功/失败）与任务文案  
2. 步数、使用过的工具 chips  
3. **工具时间线**：每步入参/输出预览  
4. **报告**：Markdown 渲染的 Findings 摘要

---

## 16. 修订历史

| 日期 | 说明 |
|------|------|
| 2026-07-26 | 初稿：对齐 CC/Codex 的自研演进方案，硬约束功能全保留 |
| 2026-07-26 | 实现 Explore Subagent 隔离探索会话并接入主路径 |
| 2026-07-26 | 顶栏模式 Chip、LoggingAgentHook、CompactPolicy 接入 compress；里程碑验收测试补全 |
