# Agent Runtime 设计文档（AUTO 意图路由与稳定执行）

> 状态：**AUTO 里程碑已实现；CHAT / PLAN / AGENT 仅保留内部兼容语义**
> 更新日期：2026-07-30
> 范围：在保留 RikkaHub 现有能力与历史数据兼容的前提下，由每个根 Run 自动选择回答、探索、执行或澄清，并冻结执行权限边界
> 相关文档：[chat-generation-pipeline.md](./chat-generation-pipeline.md)、[AUTO 设计](../plans/2026-07-30-auto-agent-intent-routing-design.md)、[AUTO 实施计划](../plans/2026-07-30-auto-agent-intent-routing-implementation.md)
>  
> 实现入口：`data/ai/agent/` 与 `data/ai/agent/routing/`，门面 `GenerationHandler`，编排 `ChatService` + `ToolRegistry`
> UI：聊天顶栏只显示不可点击的 AUTO 状态；Run Center 解释冻结路由；Explore 轨迹面板继续保留

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
| Subagent | 探索 / 通用子代理 | — | Explore 子 Agent 已实现并受只读/预算边界约束 |
| 沙箱边界 | sandbox / hooks | OS 级 sandbox + 边界说明 | 强化 proot Workspace 表述 |

**不采用** 嵌入 CC / Codex / Pi 二进制运行时的方案（栈不匹配：Kotlin/Android vs Node/Rust CLI）。

2026-07-30 之后，先前的会话级手动模式已被 Run 级 AUTO 路由取代。`AgentMode`、`Conversation.agentMode` 与数据库 `agent_mode` 没有删除，但它们只服务于内部接口映射和历史数据读取；新 UI 不再写入该字段，新 AUTO Run 的快照也把 `agentMode` 留空。

---

## 2. 目标

### 2.1 要做

1. **单一 AUTO 入口**：每个新根 Run 只根据本轮原始用户输入选择 `ANSWER / EXPLORE / EXECUTE / CLARIFY`。
2. **能力减法**：从助手实际启用的完整候选工具集出发，按 descriptor 过滤；意图只控制可见工具，不授予免审批权。
3. **不可变 Run 边界**：一次性冻结模型、Provider、Workspace、工具、权限摘要、提示上下文与 watchdog 预算；审批续跑不得重新读取会话当前模式。
4. **稳定收敛**：以 `GenerationLease`、Repository CAS、分层 watchdog 和启动恢复防止旧任务、迟到结果或进程重启污染新 Run。
5. **兼容与可观测**：历史 Run 可读取和展示；坏快照阻塞而不是扩大权限；Run Center 只展示可审计、无敏感内容的摘要。

### 2.2 明确不做（Non-goals）

| 不做 | 原因 |
|------|------|
| 嵌入 Claude Code / Codex / Pi 进程 | 运行时、包体积、生命周期、协议不适配 Android 主路径 |
| 删除或默认关闭现有工具/MCP/记忆/角色能力 | 违反「功能全保留」 |
| 替换 `UIMessage` / Room schema / Provider 栈 | 成本高、收益低、破坏兼容 |
| 把产品改成「只能写代码的 CLI」 | 与 RikkaHub 定位冲突 |
| 用 LLM 分类结果或派生内容授予 `EXECUTE` | 分类和网页/附件内容都不是可信授权源 |
| 跨进程恢复 Provider 流或重放工具 | 缺少可证明的游标、幂等与输入边界；当前启动策略是中断并收敛展示 |
| 删除旧 `agent_mode` 或增加 `AgentMode.AUTO` | 本批使用路由快照表达 AUTO，不需要数据库迁移或新枚举值 |

---

## 3. 硬约束：功能全保留

以下产品能力继续存在；AUTO 会按单 Run 意图缩小本次可见工具集，但不得删除助手能力、绕过既有审批或改变数据格式契约：

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
| 工具注册顺序 | Memory → 搜索 → 本地 → 对话 → Workspace → Artifact → Explore Subagent → Skill → MCP；由 `ToolProviderOrder` 固定 |
| 审批 | `needsApproval == true` 且状态为 Auto 时 → Pending，等待用户 |
| 取消 | `CancellationException` 必须向上传播 |
| 输出截断 | 超 32KB 且存在 `workspace_shell` 时截断并落盘 |
| Input transformers 顺序 | TimeReminder → PromptInjection → Placeholder → DocumentAsPrompt → Ocr → Template → WorkspaceReminder → 冻结 ProjectDocs |
| 对外入口 | `ChatService` 仍是会话编排入口；UI / Web API 不因内部重构而改语义 |
| AUTO 授权输入 | 路由发生在 transformer、文档展开与项目指令注入之前；只有本轮直接用户文本可以授权 `EXECUTE` |
| 根 Run 所有权 | 面向会话的 active/stop/approval API 只选择 `parent_run_id IS NULL` 的根 Run；父 Run 收敛时一并收敛活跃子 Run |
| 写回 fencing | chunk、工具结果、会话保存、通知、标题和建议写回前必须验证当前 `GenerationLease` 与绑定 run ID |
| 快照解码 | 旧 JSON、AUTO JSON、损坏或未知 JSON 必须分别进入 Legacy、Auto、Unavailable/Blocked，禁止坏 AUTO 回退为全工具模式 |

### 3.3 兼容原则（Compatibility Rules）

1. **候选能力全保留，单 Run 做减法**：`ToolRegistry` 仍按助手设置构造完整候选集；`ToolProfileResolver` 再按冻结意图过滤。
2. **AUTO 不是授权捷径**：`EXECUTE` 只扩大工具可见集合，`CapabilityPolicy`、`PermissionPolicy`、MCP authority 与 Workspace 沙箱仍是执行期硬边界。
3. **数据结构兼容**：`Conversation.agentMode`、数据库 `agent_mode` 与旧 `AgentRunConfigSnapshot.agentMode` 保留可读；数据库版本保持 28，无新 migration。
4. **新 Run 不读写旧模式**：AUTO 快照令 `agentMode = null`，内部仅用 `ANSWER -> CHAT`、`EXPLORE/CLARIFY -> PLAN`、`EXECUTE -> AGENT` 适配旧接口。
5. **失败关闭**：工具缺失、权限/执行上下文摘要漂移、快照损坏、冻结上下文丢失或 authority 改变都阻塞或拒绝执行，不静默换工具。
6. **进程边界明确**：持久快照支持审计，敏感执行材料只在进程内保留；进程重启中断全部活跃 Run，不跨进程续跑。

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

### 4.3 路由与执行分离（AUTO 取代手动 Plan Mode）

AUTO 先在工具和派生上下文进入 prompt 之前做可信意图判定，再建立执行边界：

1. `ANSWER`：普通回答，只暴露无副作用的本地读取/用户交互能力。
2. `EXPLORE`：只读搜索、会话、Workspace、Skill 和只读子 Agent 能力。
3. `EXECUTE`：仅当直接用户输入同时给出明确动作与目标时开放助手已启用的完整候选集；执行期仍逐工具检查审批和沙箱。
4. `CLARIFY`：目标或动作不充分时只允许询问用户。

旧 `PLAN` / `AGENT` 仍作为 prompt、preflight 和现有策略接口的兼容参数，但用户不能在聊天顶栏或 FilesPicker 手动切换。网页、附件、历史工具输出、Skill 与项目指令可以提供任务内容，不能把只读 Run 升级成执行 Run。

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
ChatService                                      # 会话编排与 Run 所有权
    ├── IntentRouter(raw user parts)              # 纯函数、可信输入边界
    ├── ToolRegistry(mode = AGENT)                # 构造助手完整候选集
    │     └── ToolProfileResolver                 # descriptor 驱动的能力减法
    ├── AgentRunPlanner                           # 生成/验证不可变 AUTO 快照
    ├── FrozenRunExecutionContext                 # 进程内敏感执行材料
    └── ConversationSession.GenerationLease       # epoch + Job + run ID fencing
              │
              ▼
GenerationHandler → AgentLoop                    # 兼容门面与 step 循环
    ├── Provider idle watchdog
    ├── CapabilityPolicy + PermissionPolicy
    ├── Tool watchdog + result CAS
    ├── ProjectDocs / transformers / context governor
    └── Hooks / Compact / Explore Subagent
              │
              ▼
AgentRunRepository + Room                        # 根 Run CAS、审批与启动收敛
ProviderManager + UIMessage                      # Provider 与消息模型保持
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
│   ├── routing/
│   │   ├── AgentRouting.kt           # intent / trust / 持久路由快照
│   │   ├── AgentRoutingSnapshotCodec.kt
│   │   ├── IntentRouter.kt
│   │   ├── ToolProfileResolver.kt
│   │   └── AgentRunPlanner.kt
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

> `GenerationHandler` 仍是兼容门面；Run 级编排、冻结上下文与写回 fencing 位于 `ChatService`。

### 5.3 核心类型（当前实现）

#### AgentIntent 与 AgentMode 兼容映射

`AgentIntent` 是新 Run 的产品与安全语义：`ANSWER / EXPLORE / EXECUTE / CLARIFY`。`InputTrust` 是 `USER_DIRECT / DERIVED_UNTRUSTED`；派生输入绝不能形成有效的 `EXECUTE` 快照。

`AgentMode` 没有增加 `AUTO`，只在旧 prompt、preflight 和 policy 接口内使用：

```text
ANSWER              -> CHAT
EXPLORE / CLARIFY   -> PLAN
EXECUTE             -> AGENT
```

该映射不是用户可切换状态，也不是新的授权来源。

#### AgentRoutingSnapshot

持久快照只保存无内容的策略事实：版本、intent、trust、稳定 reason code、唯一排序后的工具名、`permissionDigest`、`executionContextDigest`、Provider idle / tool / Run 超时。AUTO 快照使用 `runtimeVersion = agent-loop-v3`、`toolPolicyVersion = auto-intent-v1`、`agentMode = null`；完整配置 JSON 上限为 64 KiB。

快照不保存 prompt、消息、工具参数/输出、Header、Body、密钥或 OAuth token。严格 codec 输出 `Auto / Legacy / Invalid`；声明 AUTO 却缺失 routing、未知版本、无效 digest 或超限内容都属于 Invalid，不能降级成 Legacy。

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

执行期映射规则：

- 当前 `needsApproval == false` → `AUTO`  
- 当前 `needsApproval == true`（如 AskUser）→ `ASK`  
- AUTO 的 `EXPLORE / CLARIFY` 兼容映射为 Plan，因此写、Shell、Memory mutation 和 MCP 不进入可见工具集；执行期 policy 仍会硬拒绝遗漏路径

#### ProjectDocsLoader

加载顺序建议（对齐 Codex 级联思想，适配 Workspace）：

1. Workspace 根目录：`AGENTS.md` / `CLAUDE.md` / `RIKKA.md`（文件名优先级可配置）  
2. 从 workspace 根到当前 `cwd` 路径上的同名文件（深层覆盖浅层冲突项，或简单拼接并标注来源）  
3. 总大小上限：默认 **32 KiB**（对齐 Codex 量级，可配置）  
4. 优先级：用户消息 / 助手 systemPrompt / 对话 customSystemPrompt **高于** 项目文档

无 Workspace 或文件不存在时：**不注入任何内容**（与现状一致）。

---

## 6. 与现有链路的关系

### 6.1 新 Run 主路径

```
本轮原始 UIMessagePart
  → RuleBasedIntentRouter（先移除代码块、行内代码与引用段）
  → ToolRegistry 以 AGENT 兼容模式构造助手完整候选集
  → ToolProfileResolver 按 intent / trust / descriptor 做能力减法
  → ProviderPreflight + AgentRunPlanner
  → 原子 replaceActiveRun，持久化不可变 AgentRunConfigSnapshot
  → GenerationHandler / AgentLoop 消费同一个 AgentRunPlan
  → lease 校验后写 Conversation、事件、通知、标题与建议
```

`sendMessage` 的路由发生在 `preprocessUserInputParts()`、OCR、文档展开、Template、ProjectDocs 与 Workspace reminder 之前。重新生成使用历史用户消息，但标记为 `DERIVED_UNTRUSTED`；即使命中修改词，也会降为 `EXPLORE`。第一阶段不使用 LLM 分类器授予 `EXECUTE`。

`ToolProfileResolver` 的结果按名称去重并稳定排序。`ANSWER` 只保留无副作用本地读取和用户交互；`EXPLORE` 只保留搜索、会话/Workspace/Skill 读取、只读子 Agent 与用户交互；`CLARIFY` 只保留用户交互；`EXECUTE` 保留候选集，但 descriptor、`CapabilityPolicy`、审批与沙箱都不改变。未知工具和 MCP 不会进入非执行 Profile。

### 6.2 持久快照与进程内冻结上下文

执行边界分成两层，不能互相替代：

| 层 | 内容 | 生命周期 |
|----|------|----------|
| `AgentRunConfigSnapshot.routing` | intent、trust、reason code、工具名、权限/执行上下文摘要、三个 timeout、模型/Provider/Workspace ID 与能力摘要 | Room 持久化，用于审计、严格解码与漂移验证 |
| `FrozenRunExecutionContext` | `Settings`、Assistant、Model、Provider、Workspace、PermissionPolicy、已描述工具实例、会话 prompt、mode/lorebook IDs、CWD、ProjectDocs 文本快照、Memory、Preflight 结果与发送者显示名 | 仅当前进程内，私有且不序列化；等待审批时复用 |

`executionContextDigest` 是无内容摘要，只编码 run/conversation/assistant/model/provider/workspace 身份和会影响执行的布尔/ID 集合。prompt、凭据、自定义 Header/Body、消息、工具参数与输出不会进入摘要或 Run telemetry。

审批续跑必须找到同一个 `FrozenRunExecutionContext`，并由 `AgentRunPlanner` 验证根 Run、可续跑状态、conversation/assistant/model/provider/workspace、能力摘要、权限摘要、执行上下文摘要和完整工具集合。缺失上下文、工具缺失或任一摘要漂移都会把 Run 置为 `BLOCKED`；不会读取新的 `Conversation.agentMode`，也不会用当前设置重建一套近似环境。

Authority 在工具调用边界继续检查：

- Workspace 冻结 shell `READY` 状态、root 与工具审批覆盖；Repository 工具按预期 root 执行，Workspace 漂移不会扩大路径范围。
- MCP 冻结 transport、URL、client name、启用状态、静态 Header、OAuth authority（不含 bearer/refresh token）与单工具 enable/approval policy；调用前同时比较当前设置、冻结配置与已连接 session。
- OAuth token 可以刷新，但只有 authority、scope 与工具策略不变时才能通过 CAS 发布和继续调用；服务器被删除、禁用、改地址、改 Header、撤销助手绑定或改工具审批后，旧 Run 拒绝调用。
- Settings 的后续 UI 更新不会改写已有 Run；当前 Settings 只可作为撤销/authority 检查，不作为续跑的新配置来源。

### 6.3 `GenerationLease` 与 CAS fencing

所有生成 Job 以 `CoroutineStart.LAZY` 创建，先安装后启动。`ConversationSession.install()` 原子递增 epoch，并返回包含 epoch、Job 和被替换 run ID 的 `GenerationLease`；绑定后，lease 只能代表该 run。安装 B 会立即使 A 的 lease 失效，A 的 completion callback 也不能清除 B。

会话写回必须同时满足“epoch + 同一 Job + run ID + 冻结上下文”仍为当前所有者。流式 chunk、processing status、结束事件、通知、错误、标题和建议都经过这一边界；生成替换与保存边界还由每会话 generation mutex 串行化。Repository 是第二层 fencing：Run、Step、Tool 和 Approval 都用期望状态 CAS。

删除也使用同一把会话 generation mutex：先设置生命周期阻断标记，再取消并等待当前 Job 收敛，最后清理 session 和持久数据。生成安装在锁内再次核对删除标记、session 身份和 lease，避免“检查通过后被删除、随后又安装 Job”的 TOCTOU。以相同 UUID 导入已删除会话必须走 `ChatService.restoreImportedConversation()`；旧 session 先 detach，数据持久化成功后才解除 tombstone。Web SSE 通过单会话 `EXISTS` Flow 观察删除，目标行消失即结束流并释放 session handle。

Settings 的 whole-object UI 写入带 revision；过期快照按稳定 ID 和字段做三方合并。Assistant、Provider 及其嵌套模型的非冲突修改会保留，当前删除不会被旧快照复活；MCP/OAuth 继续使用专门的 authority 与凭据合并规则。

工具结果只有在 `AgentRunRuntime.toolFinished()` CAS 返回 `true` 后，才会加入 `UIMessagePart.Tool` 并记录成功 trace；CAS 失败代表结果已迟到，输出直接丢弃。取消在 `NonCancellable` 中收敛持久状态后重新抛出原 `CancellationException`，不能被外层当成普通失败。替换/停止的展示与持久化收敛有 10 秒边界；即使第三方调用不响应取消，失效 lease 仍禁止它写回。

### 6.4 分层 watchdog

| 层 | 当前语义 |
|----|----------|
| Provider | 默认 45 秒无进展；流式调用每收到一个 chunk 重新计时，非流式调用包裹整个 await；到期抛出 message 为 `PROVIDER_IDLE_TIMEOUT` 的 `ProviderIdleTimeoutException` 并使 Run 失败 |
| Tool | 使用 `ToolDescriptor.timeoutMillis`，默认 30 秒；`workspace_shell` 为 610 秒，`explore_subagent` 为 125 秒，以覆盖各自内部 600/120 秒预算 |
| Run phase | 每个正在执行的 generation phase 使用快照中的 30 分钟 `runTimeoutMillis`；进入 `WAITING_APPROVAL` 后该 Job 已结束，续跑启动新 phase 但复用同一冻结预算值 |

普通工具与并行 Explore 共用 `executeToolWithWatchdog()`。本地 timeout 形成结构化 `TOOL_TIMEOUT` 工具失败；父协程取消优先，不能被误报为 timeout。Run phase 总预算到期走 cancellation convergence，当前持久终态为 `CANCELLED`，并无单独的 `TIMED_OUT` Run 状态。

### 6.5 审批续跑与保留策略

审批由 run ID、approval ID、tool execution ID、step、tool name、tool call ID、canonical input digest、policy/workspace/MCP 摘要共同绑定。过期或重复 UI 事件不能命中另一个 Run 或另一次同名调用。持久审批授权默认 5 分钟；若授权记录到期，服务只能为同一 execution 生成替代审批并重新绑定原卡片，不能直接执行。

等待审批所需的完整冻结上下文只存在内存，因此另设保留边界：最多 16 个 `WAITING_APPROVAL` 上下文、最长 24 小时。超出容量时先处理最旧项；容量淘汰或 TTL 到期都先关闭未完成展示，再以 `APPROVAL_CONTEXT_CAPACITY` 或 `APPROVAL_CONTEXT_EXPIRED` 阻塞 Run，最后释放上下文。Run 离开等待态、用户停止、被替换或服务清理时会取消 retention Job。

旧快照仍可被 planner 解码为 Legacy 并在 Run Center 展示，但当前 `ChatService` 不会把它升级成 AUTO。没有本批冻结上下文的 Legacy 审批续跑会以 `LEGACY_CONTEXT_NOT_FROZEN` 失败关闭。

### 6.6 进程启动恢复

应用启动先关闭 generation gate，再调用 `interruptActiveRunsOnStartup()` 扫描全部活跃根/子 Run；它不恢复 Provider 流或重放工具：

1. Run 转为 `INTERRUPTED`，活跃 Step 转为 `CANCELLED`。
2. `PENDING / WAITING_APPROVAL` 工具转为 `CANCELLED`；已经 `AUTHORIZED / RUNNING` 的工具转为 `UNKNOWN_AFTER_INTERRUPT`。
3. 待审批授权取消，Run 先写入耐久标记 `PROCESS_INTERRUPTED_PENDING_PRESENTATION`。
4. `ChatService` 关闭未完成 reasoning 和工具卡片、清空 processing status，并协调清理关联通知。
5. 展示收敛成功后用 CAS 把标记改成 `PROCESS_INTERRUPTED`，才打开 generation gate。

若进程在第 3～5 步再次死亡，下次启动会重新扫描带 pending marker 的 `INTERRUPTED` Run。任一恢复步骤失败时 gate 以异常完成，本进程的新生成失败关闭，避免新 Run 覆盖未收敛卡片。

### 6.7 AUTO 与 Legacy UI

- 聊天顶栏只有不可点击的“自动”；活跃 AUTO Run 显示“自动 · 回答/探索/执行/需确认”。FilesPicker 不再有模式入口。
- Run Center 严格解码快照：AUTO 展示 trust、白名单 reason code、工具数/截断列表、权限摘要预览和策略版本；Legacy 显示 `Legacy · Chat/Plan/Agent`；无效快照显示通用不可用/降级原因。
- 未知 reason code 不原样显示，工具列表最多预览 8 项且单名截断，权限摘要只显示审计预览；prompt、参数、密钥和完整 MCP 配置不进入 UI。
- 活跃详情只有在 detail run ID 等于当前 active run ID 时才参与 presentation，否则回退到当前摘要，避免 A 的详情和 B 的停止按钮错配。

**ChatService 对外方法、Web API 与 UI 订阅的 Flow 形状保持稳定；Web/SSE 在使用 session flow 前持有显式 session handle。**

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

### Phase 3 — Plan Mode（历史里程碑，UI 已由 Phase 6 取代）

- [x] `AgentMode.PLAN` / `AGENT`  
- [x] Plan 下过滤写/shell 工具；执行时硬拒绝兜底  
- [x] 曾提供 Workspace 模式切换；Phase 6 已移除入口和新写入
- [x] `Conversation.agentMode` / Room 字段保留历史读取兼容

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

### Phase 6 — AUTO 意图路由与稳定性

- [x] `IntentRouter`、`ToolProfileResolver`、严格 snapshot codec 与 `AgentRunPlanner`
- [x] 冻结模型、Provider、Workspace、工具、权限、prompt/Memory/ProjectDocs 与 timeout 策略
- [x] `GenerationLease`、根 Run CAS、迟到结果/通知/旁路生成 fencing
- [x] Provider、Tool、Run phase 分层 watchdog 与非取消收敛
- [x] WAITING_APPROVAL 上下文 TTL/容量治理和精确 continuation binding
- [x] 启动时耐久中断标记、展示/通知修复与 fail-closed generation gate
- [x] 唯一不可点击 AUTO 状态、Legacy/Unavailable Run Center 展示
- [x] 数据库版本保持 28，旧 `agent_mode` 只读兼容

---

## 8. 功能保留对照表

| 现有能力 | 当前归属 | AUTO 语义 |
|----------|----------|-----------|
| GenerationHandler loop | AgentLoop | 启用 |
| 搜索工具 | SearchToolProvider + ToolProfileResolver | 助手启用后可进入 EXPLORE/EXECUTE |
| 本地工具 | LocalToolProvider + descriptor | 按副作用与 intent 做减法 |
| 对话检索 | ConversationToolProvider | 助手启用后可进入 EXPLORE/EXECUTE |
| Workspace | WorkspaceToolProvider + frozen authority | READY 且 root/审批快照匹配；写/Shell 仅 EXECUTE 可见 |
| Skills | SkillToolProvider | Skill 读取可探索；派生内容不能授权执行 |
| MCP | McpToolProvider + frozen connection/tool policy | 仅 EXECUTE 可见，调用时再次校验 authority |
| Memory | MemoryToolProvider | 读取/写入按 descriptor 和既有审批边界过滤 |
| Transformers 管道 | AgentLoop 调用原 transformers | 顺序不变，但意图路由发生在管道之前 |
| 审批 UI | PermissionPolicy + 持久 binding + frozen continuation | 原卡片保留，续跑不可换策略 |
| 角色卡 / Lorebook / 正则 | 原链路 | 不变 |
| Web / 分支 / 通知 | ChatService + session handle + lease | 对外形状不变，异步写回增加 fencing |
| 旧 AgentMode 数据 | Legacy codec / Run Center | 可读可展示，不再影响新 Run |

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 重构导致工具顺序变化 | 模型选工具行为漂移 | `order` 写死 + 快照测试 |
| 规则误判为执行 | 非预期副作用 | EXECUTE 只接受直接用户文本，必须同时有动作与目标；policy/审批二次校验 |
| 派生内容提示注入 | 网页或文档尝试升级权限 | 路由先于 transformer；`DERIVED_UNTRUSTED` 强制禁止有效 EXECUTE |
| 设置或 authority 漂移 | 审批后换模型、工具或连接 | 快照/digest 校验 + Workspace/MCP 调用期 authority 检查，漂移时失败关闭 |
| 不合作的 Provider/工具 | 会话永久卡住 | Provider/Tool/Run 分层 watchdog + 失效 lease 丢弃迟到结果 |
| 进程死于恢复中间态 | 终态 Run 留有 pending 卡片 | 耐久 pending marker + 启动 gate + 幂等 presentation reconciliation |
| 内存审批上下文无限增长 | 长时间占用敏感执行材料 | 24 小时 TTL、16 项容量上限、终态/停止主动清理 |
| AGENTS.md 挤占 context | 有效对话变短 | 32KiB 上限；可关；来源可折叠 |
| 文档与代码漂移 | 后续贡献者踩坑 | Phase 完成时同步更新 pipeline 文档 |

---

## 10. 测试策略

### 10.1 单元测试

- `IntentRouter`：中英文回答/探索/执行/澄清、否定优先、代码/引用剥离、派生输入降级
- `ToolProfileResolver`：四种 intent 的能力集合、unknown/MCP、稳定排序、长工具 timeout
- `AgentRoutingSnapshotCodec`：Auto/Legacy/Invalid、64 KiB 边界、版本与 digest 校验
- `AgentRunPlanner`：完整冻结快照、根 Run 边界、工具/权限/执行上下文漂移阻塞
- `ConversationSession`：lazy install、epoch/Job fencing、绑定、替换 completion 与并发 install
- `AgentLoopWatchdog`：工具 timeout、Provider idle timeout、父取消优先、`toolFinished` CAS 丢弃
- `SettingsRevisionState`、Workspace/MCP provider：旧设置合并与 frozen authority 防漂移
- `AgentRunPresentation`：AUTO/Legacy/Unavailable、reason code 白名单与 active detail run ID
- `ToolRegistry` / `PermissionPolicy`：候选工具顺序、descriptor 与执行期二次策略
- `ProjectDocsLoader`：级联、上限、缺失文件  
- 审批状态机：Auto→Pending→Approved/Denied/Answered、过期重绑和 retention 收敛

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
10. 快速发送 A/B，确认 A 的 chunk、工具结果、通知、标题与建议都不再写回
11. 等待审批时修改助手/模型/Workspace/MCP 设置，确认续跑复用旧边界或失败关闭
12. Provider、普通工具、Explore 和 Run phase timeout 后仍可发送新消息
13. 杀进程后确认 Run 为 INTERRUPTED、卡片/通知收敛且不自动重放
14. Web 端发送、编辑、审批、删除与 SSE session 生命周期

---

## 11. 决策记录（ADR 摘要）

| ID | 决策 | 理由 |
|----|------|------|
| ADR-1 | 不嵌入 CC/Codex/Pi 运行时 | 栈与产品形态不匹配 |
| ADR-2 | 借鉴 Pi 的小 loop/取消边界与 Hermes 的组合式 toolset/不可信输入边界，不引入其运行时 | RikkaHub 保持 Kotlin/Android 与完整能力栈 |
| ADR-3 | 候选工具全保留，AUTO 每 Run 按 descriptor 做能力减法 | 普通问答最小权限，明确执行仍保留助手能力 |
| ADR-4 | Tool 结果仍内联 ASSISTANT parts | 兼容现 UI/DB/Provider 适配 |
| ADR-5 | 项目文档优先级低于助手/用户指令 | 与 Codex 优先级模型一致 |
| ADR-6 | 移除手动模式入口，不增加 `AgentMode.AUTO` | AUTO 是 Run 快照语义；旧字段只读兼容且无需 migration |
| ADR-7 | 持久无内容快照 + 进程内敏感冻结上下文 | 审计可持久，prompt/凭据/工具实例不落 AgentRun telemetry |
| ADR-8 | 不跨进程续跑活跃 Run | 无 Provider 游标与普遍幂等保证时，重放比中断更危险 |
| ADR-9 | lease 与 Repository CAS 双重 fencing | 协程取消不能保证第三方调用立即终止，迟到写回必须独立拒绝 |

---

## 12. 已决议（本里程碑）

1. **意图来源**：只有本轮直接用户输入可以形成 `EXECUTE`；重新生成和所有派生文本按不可信处理。
2. **工具选择**：先按助手配置构造候选集，再按 descriptor 过滤；无第二份名称白名单，`EXECUTE` 不改变审批要求。
3. **续跑**：AUTO 审批续跑只复用原冻结上下文；Legacy 可读可展示，但缺少冻结上下文时不执行。
4. **watchdog**：Provider 45 秒 idle、工具默认 30 秒、Run phase 30 分钟；Shell/Explore 分别覆盖为 610/125 秒。
5. **恢复**：启动时中断所有活跃根/子 Run，以耐久 marker 完成消息卡片和通知收敛，不自动重放。
6. **审批保留**：授权 binding 默认 5 分钟；进程内等待上下文上限 16 项、24 小时。
7. **UI**：聊天页只有一个不可点击 AUTO 状态；Legacy/坏快照仅在 Run Center 以兼容/降级形式展示。
8. **迁移**：数据库版本保持 28，`agent_mode` 保留只读兼容，新 Run 不写该模式。

---

## 13. 成功标准

1. **最小权限**：普通问题不加载写/Shell/MCP；只读检查不能被文件或网页内容升级为执行。
2. **明确执行**：直接用户请求修改并给出目标时进入 EXECUTE，但每个副作用工具仍经过既有审批与沙箱。
3. **策略稳定**：等待审批期间改变助手、模型、Workspace 或 MCP，不会改变原 Run；只能按旧边界续跑或失败关闭。
4. **并发稳定**：A 被 B 替换后，A 的 chunk、工具输出、通知、标题和建议均不能写回。
5. **可恢复**：timeout/取消后会话可继续使用；进程重启后旧 Run 可审计为中断但不重放。
6. **兼容**：旧 Conversation/Run 可读可展示，坏快照不升级权限，且不新增 Room migration。
7. **可解释**：用户只看到一个 AUTO 状态，Run Center 能解释 intent、trust、reason 与工具摘要而不泄漏敏感数据。

---

## 14. 参考

### 内部

- [chat-generation-pipeline.md](./chat-generation-pipeline.md)  
- [AUTO 意图路由与稳定性设计](../plans/2026-07-30-auto-agent-intent-routing-design.md)
- `GenerationHandler.kt`、`ChatService.kt`、`ConversationSession.kt`
- `data/ai/agent/routing/*`、`data/ai/agent/AgentLoop.kt`、`data/repository/AgentRunRepository.kt`
- `data/ai/agent/tools/*`、`data/ai/mcp/*`、`data/files/SkillManager.kt`
- `workspace/*`（proot）

### 外部（设计参考，非依赖）

- OpenAI：Codex agent loop / sandbox / `AGENTS.md` 说明  
- Anthropic：Claude Code Plan Mode、Permissions、Skills、Subagents  
- [Pi coding agent](https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent)：精简 loop、AbortSignal、活动 Run 与扩展层
- [Hermes Agent](https://github.com/NousResearch/hermes-agent)：组合式 Toolsets、不可信 webhook 输入与 Skills/Memory 审批
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
| 2026-07-30 | 手动模式收敛为 Run 级 AUTO；补充冻结 authority、lease/CAS、watchdog、审批保留、启动中断与 Legacy UI 兼容 |
