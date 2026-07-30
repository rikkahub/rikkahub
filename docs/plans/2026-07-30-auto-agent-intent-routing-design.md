# AUTO Agent 意图路由与稳定性设计

> 状态：已确认  
> 日期：2026-07-30  
> 范围：将会话级 `CHAT / PLAN / AGENT` 手动模式收敛为单一 AUTO 入口，并修复 Run 在审批、恢复和并发场景中的策略漂移。

## 1. 背景

当前实现把 `AgentMode` 持久化在 `Conversation` 上，聊天页允许用户循环切换 `CHAT / PLAN / AGENT`。新 Run 会把模式名称写入 `AgentRunConfigSnapshot`，但真正解析工具、执行 Provider 预检以及审批恢复时又会读取会话当前模式。因此，同一个 Run 可能在等待审批期间切换工具集合和权限语义，运行记录与实际行为不再一致。

此外，`CHAT` 与 `AGENT` 当前暴露相同的工具集合，差异主要存在于提示词、Provider 预检和权限摘要。这三个模式对用户是显式状态，对运行时却不是互斥能力，导致产品语义和安全边界难以推断。

本设计将模式选择改成一次 Run 一次决策，并把决策冻结为可恢复的策略快照。

## 2. 目标与非目标

### 目标

- UI 只提供一个 AUTO Agent 入口。
- 自动识别普通回答、只读探索、明确执行和需要澄清四类意图。
- 明确修改意图可以自动进入执行阶段；敏感工具仍按既有权限策略审批。
- 模糊请求保持只读或请求澄清，识别失败不得扩大权限。
- Run 创建后冻结模型、意图、工具集合、权限摘要和超时策略。
- 审批恢复、应用重启和设置变化不得改变既有 Run 的策略。
- 每个会话最多一个活跃 Run；超时或取消后，迟到结果不得写回会话。
- 保持旧 Conversation 和旧 Run 数据兼容，不为本次功能升级 Room schema。

### 非目标

- 第一阶段不实现自动创建或修改 Skills/Memory。
- 不引入 Pi 或 Hermes 的 TypeScript/Python 运行时。
- 不用单次 LLM 分类结果授予写权限。
- 不删除旧 `agent_mode` 数据列；它保留为兼容字段。
- 不改变现有工具审批 UI 和底层 Workspace 沙箱边界。

## 3. 核心模型

```kotlin
@Serializable
enum class AgentIntent {
    ANSWER,
    EXPLORE,
    EXECUTE,
    CLARIFY,
}

@Serializable
enum class InputTrust {
    USER_DIRECT,
    DERIVED_UNTRUSTED,
}

@Serializable
data class AgentRoutingSnapshot(
    val version: String = "auto-intent-v1",
    val intent: AgentIntent,
    val inputTrust: InputTrust,
    val reasonCode: String,
    val resolvedToolNames: List<String>,
    val permissionDigest: String,
    val toolTimeoutMillis: Long,
    val runTimeoutMillis: Long,
)
```

`AgentRunConfigSnapshot` 新增可选的 `routing: AgentRoutingSnapshot?`。原有 `agentMode` 字段继续保留，只用于恢复升级前创建的 Run。JSON 增加可选字段不需要 Room 迁移。

新 Run 使用 `runtimeVersion = "agent-loop-v3"` 和 `toolPolicyVersion = "auto-intent-v1"`。旧 Run 若没有 `routing`，继续按其 `agentMode` 执行，不能在恢复时升级成 AUTO。

## 4. 意图路由

`IntentRouter` 是纯函数，不访问数据库、不调用工具，也不改变会话：

```kotlin
data class IntentRoutingRequest(
    val directUserText: String,
    val hasWorkspace: Boolean,
    val hasAttachments: Boolean,
)

data class IntentRoutingDecision(
    val intent: AgentIntent,
    val inputTrust: InputTrust,
    val reasonCode: IntentReasonCode,
)
```

决策顺序：

1. 只检查本轮用户直接输入是否明确要求创建、修改、修复、删除、运行或应用变更。
2. 明确副作用意图且运行环境满足要求时返回 `EXECUTE`。
3. 明确搜索、读取、解释、审查或分析项目时返回 `EXPLORE`。
4. 不需要工具的普通问答返回 `ANSWER`。
5. 目标或动作不清楚时返回 `CLARIFY`。

网页内容、附件文本、历史工具输出、Skill 内容和项目指令文件均标记为派生输入。它们可以影响任务内容，但不能把 `ANSWER / EXPLORE` 升级为 `EXECUTE`。只有本轮用户直接指令能授予执行意图。

第一阶段采用可测试的本地规则完成 `EXECUTE` 识别。可选的 `SafeIntentRefiner` 只能在 `ANSWER / EXPLORE / CLARIFY` 之间细分，永远不能产生 `EXECUTE`；调用失败时使用本地决策。这样可以利用模型理解自然语言，但不会把不稳定分类变成权限授予。

## 5. 工具 Profile

`ToolProfileResolver` 在现有 `ToolRegistry` 和 `ToolDescriptor` 之后工作。它先收集当前助手实际可用的工具，再根据冻结意图做最终过滤：

| 意图 | 工具能力 |
| --- | --- |
| `ANSWER` | 无副作用本地能力、用户澄清；默认不提供 Workspace 写入或 Shell |
| `EXPLORE` | 搜索、会话读取、Workspace 读取、Artifact 读取、Skill 读取、只读子 Agent、用户澄清 |
| `EXECUTE` | 当前助手启用的全部工具，继续受 CapabilityPolicy 和审批规则约束 |
| `CLARIFY` | 仅用户澄清能力；不可执行其他工具 |

过滤规则使用 `ToolDescriptor.capability` 与 `sideEffect`，不再维护第二份按工具名硬编码的 Plan 白名单。未知工具和 MCP 工具在 `ANSWER / EXPLORE / CLARIFY` 中默认不暴露；它们只有在 `EXECUTE` 中出现，并继续走高风险审批。

最终 `resolvedToolNames` 排序后写入 Run 快照。审批恢复时可以重新构造工具实例，但只能取快照名称与当前可用工具的交集；若快照要求的工具已不可用，应阻塞 Run 并显示明确错误，不能静默换成另一套工具。

## 6. Run 生命周期

```text
用户消息
  -> IntentRouter
  -> 创建 Agent Run
  -> 解析工具并持久化 AgentRoutingSnapshot
  -> ProviderPreflight
  -> AgentLoop
  -> 审批/恢复（复用同一快照）
  -> 终态
```

新 Run 的处理顺序：

1. 在会话 generation lock 内取消并等待旧 Run 结束。
2. 对最后一条用户消息做意图路由。
3. 创建 `QUEUED/PREFLIGHT` Run。
4. 用新 Run ID 解析工具，形成完整路由快照并原子更新配置。
5. 使用快照执行预检、提示词拼装和 AgentLoop。

继续旧 Run 的处理顺序：

1. 根据 `continuationRunId` 加载 Run。
2. 验证它仍属于当前会话且处于可恢复状态。
3. 若存在 `routing`，全部运行参数由它恢复；不得读取 `Conversation.agentMode`。
4. 若是旧 Run，则只按原 `agentMode` 兼容路径恢复。

对仍依赖 `AgentMode` 的内部接口提供临时映射：`ANSWER -> CHAT`、`EXPLORE/CLARIFY -> PLAN`、`EXECUTE -> AGENT`。后续逐步把 Permission、Prompt 和 Preflight 接口改为直接接受 `AgentIntent`，但不要求在第一提交中一次性删除所有兼容代码。

## 7. 权限与不可信输入

意图只决定“哪些工具可见”，不决定“工具是否免审批”。工具执行前必须再次调用 `CapabilityPolicy`：

- `ToolProfileResolver` 未暴露的工具直接拒绝。
- `PermissionPolicy` 要求审批时保持审批。
- 高风险、未知、MCP 或存在副作用的工具不得因 `EXECUTE` 自动免审。
- Workspace 不可用时拒绝 Workspace 工具。
- 子 Agent 继续使用自己的隔离边界和只读约束。

参考 Hermes 的 webhook 安全工具集，若任务的动作要求来自网页、文档或工具输出而不是用户直接指令，则当前 Run 不得升级到 `EXECUTE`。模型需要执行这些动作时，必须先结束为 `CLARIFY`，由用户的新消息创建新 Run。

## 8. 并发、取消与 Watchdog

`RunCoordinator` 负责单会话单 Run 所有权，复用现有 `ConversationSession` 和 generation lock，但增加不可伪造的 run lease。每次消息写回、工具结果写回和完成通知都验证 lease 仍属于当前 Run。

超时分层：

- Provider 单轮无进展超时。
- 每个工具使用 `ToolDescriptor.timeoutMillis`，缺省 30 秒。
- Run 设置总时间预算，缺省 30 分钟。
- 取消先走协作式 Job cancellation；宽限期后仍未结束则终止 lease、将 Run 标为失败或取消并释放会话所有权。

硬取消不能真正杀死 Android 进程内不可中断的第三方调用，因此安全关键是撤销 lease：迟到回调可以结束，但不能再更新 Conversation、AgentRun 或通知状态。工具实现仍应尽可能响应 coroutine cancellation。

## 9. UI

- 删除聊天顶栏与 FilesPicker 中的 `CHAT / PLAN / AGENT` 循环切换。
- 空闲时显示“自动”。
- 活跃 Run 显示“自动 · 回答 / 探索 / 执行 / 待确认”。
- 该状态只用于解释，不允许在 Run 中途点击切换。
- Run Center 展示 `intent`、`reasonCode`、`inputTrust`、冻结工具数量、策略版本和降级原因，不展示敏感提示词或工具参数。
- `CLARIFY` 使用既有询问用户能力或普通助手文本呈现，不新增可执行权限。

## 10. 兼容与迁移

- `Conversation.agentMode` 与数据库 `agent_mode` 保留，读取时不再影响新 Run。
- UI 不再写入 `agent_mode`。
- 新 Run 总是写 `routing`。
- 升级前已进入等待审批状态的 Run 继续按 `agentMode` 完成。
- 序列化未知值或损坏快照时阻塞 Run，不能回退为全工具模式。
- 不增加 Room v29；只更新 Kotlin 序列化模型和相关展示逻辑。

## 11. 测试与验收

### 单元测试

- `IntentRouterTest`：中英文回答、探索、明确修改、模糊请求、否定句、引用命令和提示注入文本。
- `ToolProfileResolverTest`：能力集合、未知工具、MCP、最终禁用减法和稳定排序。
- `RunPolicySnapshotTest`：序列化兼容、旧快照读取、设置变化后不可变。
- `RunCoordinatorTest`：单 Run 所有权、替换、取消、超时、迟到更新拒绝。
- `CapabilityPolicyTest`：Profile 与执行前策略双重防线。

### 集成测试

- 新消息取消旧 Run 后创建新快照。
- 用户在等待审批期间改变会话或助手设置，恢复仍使用旧快照。
- 应用恢复时新 Run 与旧 Run 分别走正确策略。
- Provider 或工具超时后会话可继续发送消息。
- `EXPLORE` 无法执行写文件、Shell、Memory mutation 或 MCP。

### 构建门槛

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :search:testDebugUnitTest
.\gradlew.bat :workspace:testDebugUnitTest
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

所有命令必须通过；若 Android SDK 环境不支持某个 Workspace 平台测试，需要记录具体排除项，不能用空测试代替。

## 12. 分阶段交付

### 第一阶段：稳定 AUTO

- IntentRouter 与 ToolProfileResolver。
- 冻结 RunPolicySnapshot。
- 审批恢复不再读取 Conversation 模式。
- 单 Run lease、超时和迟到写回防护。
- AUTO UI 与 Run Center 可观测性。

### 第二阶段：受控学习

- 任务完成后的结构化复盘。
- 建议创建或更新 Skill，但先生成 staged diff。
- Skill/Memory 写入分别配置审批，默认需要用户确认。
- 记录来源、内容摘要和版本，不允许后台学习直接修改运行权限。

## 13. 参考

- Pi 保持核心 agent loop 精简，将计划、权限与子 Agent 等工作流放在扩展层：<https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/usage.md>
- Pi 对活动 Run、steer/follow-up、AbortSignal 与工具执行门的处理：<https://github.com/badlogic/pi-mono/blob/main/packages/agent/src/agent.ts>、<https://github.com/badlogic/pi-mono/blob/main/packages/agent/src/agent-loop.ts>
- Hermes 的组合式 Toolsets 与不可信 webhook 安全工具集：<https://github.com/NousResearch/hermes-agent/blob/main/toolsets.py>
- Hermes 对 Skills/Memory 写入审批的设计：<https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/configuration.md>

