# AUTO Agent Intent Routing Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将聊天页的 `CHAT / PLAN / AGENT` 三模式收敛为单一 AUTO 入口，让每个根 Run 根据用户直接输入安全地选择回答、探索、执行或澄清，并修复审批续跑、并发替换、超时和迟到写回导致的稳定性问题。

**Architecture:** 新 Run 先由纯函数 `IntentRouter` 判定意图，再由 `ToolProfileResolver` 从完整候选工具集中做能力减法，并把模型、意图、工具名称、权限摘要和超时预算一次性冻结进 `AgentRunConfigSnapshot`。`ChatService` 只消费冻结后的 `AgentRunPlan`；审批续跑严格恢复该计划，不再读取会话当前模式。`ConversationSession.GenerationLease` 为所有异步写回提供 fencing，Repository 的状态 CAS 和分层 watchdog 作为第二道防线。旧 `agent_mode` 和旧 Run 只保留兼容读取，不新增 Room migration，也不给 `AgentMode` 增加 `AUTO` 枚举值。

**Tech Stack:** Kotlin、kotlinx.coroutines、kotlinx.serialization、Room、Jetpack Compose、JUnit、AndroidX Test、Gradle

---

## 实施约束

- 全程保留用户未跟踪目录 `_apk_dl2/`，不得读取、移动、删除、格式化或提交。
- AUTO 路由只读取本轮原始用户文本；网页、附件、历史工具输出、Skill 和项目指令不能授予 `EXECUTE`。
- `EXECUTE` 只扩大“可见工具集合”，不能绕过 `CapabilityPolicy`、`PermissionPolicy` 或 Workspace 沙箱。
- 新 Run 的配置创建后不可修改。进程重启继续调用 `interruptActiveRunsOnStartup()`，不重放活跃 Run。
- 每个任务先写失败测试，再写最小实现；每个任务结束都运行目标测试并形成一个小提交。

## Task 1：建立 AUTO 路由契约和严格快照解码

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/routing/AgentRouting.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/routing/AgentRoutingSnapshotCodec.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/AgentRun.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/AgentRunRepository.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/routing/AgentRoutingSnapshotTest.kt`

**Step 1：写失败测试**

覆盖以下契约：

- `AgentIntent` 为 `ANSWER / EXPLORE / EXECUTE / CLARIFY`。
- `InputTrust` 为 `USER_DIRECT / DERIVED_UNTRUSTED`。
- 新快照 JSON round-trip 后字段不变，工具名去重并稳定排序。
- 没有 `routing` 的旧快照解码为 `Legacy(agentMode)`。
- 带 AUTO 标记但 routing 缺失、损坏、未知版本或空 digest 的快照解码为 `Invalid`，不能降级成 `AGENT`。
- 64 KiB 边界内的较大 MCP 工具列表可保存，越界仍明确拒绝。

**Step 2：运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.routing.AgentRoutingSnapshotTest"
```

Expected: FAIL，缺少 routing 类型和严格解码器。

**Step 3：写最小实现**

- 在 `AgentRunConfigSnapshot` 增加带默认值的 `routing: AgentRoutingSnapshot? = null`。
- 快照只保存短 `reasonCode`、唯一排序后的 `resolvedToolNames`、`permissionDigest`、Provider/工具/Run 超时和策略版本；不保存 prompt、工具参数、密钥或工具输出。
- 定义 `DecodedRouting.Auto`、`DecodedRouting.Legacy`、`DecodedRouting.Invalid`，让调用方必须显式处理坏快照。
- 将 `MAX_CONFIG_JSON_BYTES` 从 8 KiB 提升到有测试保护的 64 KiB；仍在写数据库前拒绝超限内容。
- 保持 `JsonInstant` 的旧字段兼容，不改数据库版本。

**Step 4：运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.routing.AgentRoutingSnapshotTest"
```

Expected: PASS。

**Step 5：提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/agent/routing app/src/main/java/me/rerere/rikkahub/data/model/AgentRun.kt app/src/main/java/me/rerere/rikkahub/data/repository/AgentRunRepository.kt app/src/test/java/me/rerere/rikkahub/data/ai/agent/routing/AgentRoutingSnapshotTest.kt
git commit -m "feat(agent): add strict auto routing snapshot"
```

## Task 2：实现保守、确定性的意图识别

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/routing/IntentRouter.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/routing/IntentRouterTest.kt`

**Step 1：写失败测试**

至少包含中英文参数化用例：

- “解释这段代码 / what does this mean” -> `ANSWER`。
- “检查项目为什么崩溃 / inspect the repository” -> `EXPLORE`。
- “修复这个 bug 并运行测试 / edit the file and run tests” -> `EXECUTE`。
- “帮我弄一下”或动作缺少目标 -> `CLARIFY`。
- “不要修改，只分析”“do not edit” -> `EXPLORE`，否定优先于动作词。
- 代码块、引用文本或附件里出现“删除/运行命令”不能把直接用户请求升级到 `EXECUTE`。
- 空白、只有附件、只有网页派生文本 -> `CLARIFY` 且 trust 不是执行授权来源。

**Step 2：运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.routing.IntentRouterTest"
```

Expected: FAIL，`IntentRouter` 尚不存在。

**Step 3：写最小实现**

- 输入只接受 `directUserText`、Workspace 是否可用、是否有附件等事实，不接受已转换后的完整 prompt。
- 先剔除 fenced code、行内代码和引用段，再按“禁止修改 -> 明确副作用 -> 明确只读 -> 普通问答 -> 澄清”顺序决策。
- `EXECUTE` 必须同时命中明确动作和明确目标；Workspace 不可用时给出稳定 reason code 并降为 `CLARIFY`。
- 第一阶段不调用 LLM 分类器。若以后增加 `SafeIntentRefiner`，接口只能在 `ANSWER / EXPLORE / CLARIFY` 之间选择。

**Step 4：运行测试确认通过并回归注入用例**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.routing.IntentRouterTest"
```

Expected: PASS。

**Step 5：提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/agent/routing/IntentRouter.kt app/src/test/java/me/rerere/rikkahub/data/ai/agent/routing/IntentRouterTest.kt
git commit -m "feat(agent): route user intent conservatively"
```

## Task 3：按能力描述符解析 AUTO 工具 Profile

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/routing/ToolProfileResolver.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/permission/ToolDescriptor.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/routing/ToolProfileResolverTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/permission/ToolDescriptorRegistryTest.kt`

**Step 1：写失败测试**

断言：

- `ANSWER` 只保留无副作用的本地读取和用户交互能力。
- `EXPLORE` 只保留 `SEARCH / LOCAL_READ / CONVERSATION_READ / WORKSPACE_READ / SKILL_READ / SUBAGENT_READ / USER_INTERACTION` 且 `sideEffect == NONE`。
- `CLARIFY` 只保留用户交互能力。
- `EXECUTE` 保留候选工具，但并不改变 descriptor 的审批要求。
- 未知工具和 `MCP` 在非执行 profile 中被拒绝。
- 输出按工具名去重、稳定排序；同名 descriptor 冲突直接失败。
- `workspace_shell` 外层超时不少于 610 秒，`explore_subagent` 不少于 125 秒，保留内部 600 秒/120 秒预算。

**Step 2：运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.routing.ToolProfileResolverTest" --tests "me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptorRegistryTest"
```

Expected: FAIL，缺少 resolver，且两个长任务 descriptor 仍使用通用 30 秒。

**Step 3：写最小实现**

- `ToolRegistry` 仍负责构造工具实例；AUTO 新 Run 用兼容 `AgentMode.AGENT` 收集完整候选集，再由 resolver 做最终减法。
- 所有过滤依据 `ToolCapability`、`ToolSideEffect` 和 MCP/unknown 元数据，不新增按名字维护的 Plan 白名单。
- 为 shell 和 Explore descriptor 设置覆盖预算；其他工具保留 30 秒默认值。
- 生成 `permissionDigest` 时 canonicalize 已解析名称、descriptor 元数据、MCP policy identity、助手/Workspace 范围和审批策略；不得混入密钥。

**Step 4：运行目标测试和现有 Registry 回归**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.routing.ToolProfileResolverTest" --tests "me.rerere.rikkahub.data.ai.agent.ToolRegistryTest" --tests "me.rerere.rikkahub.data.ai.agent.permission.*"
```

Expected: PASS。

**Step 5：提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/agent/routing/ToolProfileResolver.kt app/src/main/java/me/rerere/rikkahub/data/ai/agent/permission/ToolDescriptor.kt app/src/test/java/me/rerere/rikkahub/data/ai/agent/routing/ToolProfileResolverTest.kt app/src/test/java/me/rerere/rikkahub/data/ai/agent/permission/ToolDescriptorRegistryTest.kt
git commit -m "feat(agent): resolve tools from auto intent profiles"
```

## Task 4：建立纯 `AgentRunPlanner` 和根 Run 身份边界

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/routing/AgentRunPlanner.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/dao/AgentRunDAO.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/AgentRunRepository.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/routing/AgentRunPlannerTest.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/data/repository/AgentRunRepositoryTest.kt`

**Step 1：写失败测试**

- 新计划把 `ANSWER -> CHAT`、`EXPLORE/CLARIFY -> PLAN`、`EXECUTE -> AGENT` 仅作为旧接口兼容映射。
- AUTO 续跑从快照恢复 assistant/model/workspace/tools/mode/timeout，不读取当前 Conversation 设置。
- 快照工具缺失、digest 漂移、未知版本或坏 JSON -> `Blocked`，不静默换工具或升级权限。
- Legacy 快照按已存 `agentMode` 恢复；无效 Legacy 模式拒绝。
- 同一会话中 parent 与 Explore child 同时活跃时，`getActiveRun()`、替换、审批和停止都只选根 Run。
- 替换根 Run 时，Repository 通过已有 `convergeRun()` 一并终止其活跃子 Run；全局启动恢复仍扫描全部活跃记录。

**Step 2：运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.routing.AgentRunPlannerTest"
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.repository.AgentRunRepositoryTest
```

Expected: 单元测试缺类；现有 DAO 用更新时间可能选中 child。第二条需已连接设备/模拟器。

**Step 3：写最小实现**

- `AgentRunPlanner` 输出完整不可变 `AgentRunPlan`，供新建与续跑共同消费。
- 新 Run 先生成 run ID、解析工具和完整 snapshot，再单次调用 `replaceActiveRun()`；不要新增更新 `config_snapshot_json` 的 DAO。
- 给 DAO 增加明确的 root-only 查询（`parent_run_id IS NULL`），让面向会话的 active API 使用它；保留 child 查询和启动恢复语义。
- Planner 对当前工具实例只接受“快照要求集合完全可重建且 digest 相同”；交集缺项直接阻塞。

**Step 4：运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.routing.AgentRunPlannerTest"
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.repository.AgentRunRepositoryTest
```

Expected: PASS；无设备时记录 instrumentation 未运行，但必须继续完成编译和 JVM 测试。

**Step 5：提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/agent/routing/AgentRunPlanner.kt app/src/main/java/me/rerere/rikkahub/data/db/dao/AgentRunDAO.kt app/src/main/java/me/rerere/rikkahub/data/repository/AgentRunRepository.kt app/src/test/java/me/rerere/rikkahub/data/ai/agent/routing/AgentRunPlannerTest.kt app/src/androidTest/java/me/rerere/rikkahub/data/repository/AgentRunRepositoryTest.kt
git commit -m "fix(agent): freeze plans on active root runs"
```

## Task 5：用 `GenerationLease` 消除任务安装竞态

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/ConversationSessionTest.kt`

**Step 1：写失败测试**

覆盖：

- lazy Job 安装完成前不会开始；安装返回带单调 epoch 的 `GenerationLease`。
- lease 可以绑定且只能绑定自己的 run ID。
- 安装 B 后 A 的 `invokeOnCompletion` 不能清除 B。
- A 的 lease 在 B 安装后立即失效，A 的 stop/late callback 不能命中 B。
- `cleanup()` 使当前 lease 失效并取消当前 Job。

**Step 2：运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.ConversationSessionTest"
```

Expected: FAIL，当前 `setJob()` 与 `bindRun(job)` 没有 epoch fencing。

**Step 3：写最小实现**

- 增加不可变 `GenerationLease(epoch, job)` 和同步保护的 current boundary。
- 提供 `install(job)`、`bindRun(lease, runId)`、`isCurrent(lease, runId?)`、`jobForRun(runId)`。
- 完成回调只在 epoch 和 Job 同时匹配时清理当前任务。
- 不在持有内部锁时 join/cancel 外部协程。

**Step 4：运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.ConversationSessionTest"
```

Expected: PASS。

**Step 5：提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt app/src/test/java/me/rerere/rikkahub/service/ConversationSessionTest.kt
git commit -m "fix(agent): fence generation jobs with leases"
```

## Task 6：把 AUTO Planner 接入 `ChatService`，冻结续跑策略

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/ToolRegistry.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/ToolRegistryTest.kt`

**Step 1：写失败测试**

- 路由使用 `preprocessUserInputParts()` 之前的原始 `content`；regex、文档内容和工具历史不能授予执行。
- 新 Run 的预检、prompt mode、工具集和 AgentLoop mode 全部来自同一个 `AgentRunPlan`。
- 用户在等待审批时修改 Conversation/Assistant 设置，续跑仍使用旧 snapshot。
- `resumeRunAfterApproval()` 返回 false 时不调用 `handleMessageComplete()`。
- 进程恢复后的 `INTERRUPTED` Run 不可续跑。
- 快速发送 A 再发送 B 时，只有 B 可以写 conversation、结束事件、标题和建议。

**Step 2：运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.ChatServiceTest" --tests "me.rerere.rikkahub.data.ai.agent.ToolRegistryTest"
```

Expected: FAIL，当前续跑重新读取 `conversation.agentMode`，并忽略 resume CAS 结果。

**Step 3：先修任务启动顺序**

- `sendMessage()`、`regenerateAtMessage()`、`handleToolApproval()` 改用 `launch(start = CoroutineStart.LAZY)`。
- 先 `session.install(job)` 获得 lease，再 `job.start()`；所有后续调用显式携带 lease。

**Step 4：接入 Planner 并缩小 generation lock**

- generation lock 只保护“取消旧 Run、读取/写入会话边界、建立新 plan/run”这段准备事务。
- AgentLoop 的长时间 collect 在 lock 外执行，防止不合作的工具永久占住会话 mutex。
- 新 Run 从完整 AGENT 候选集中解析再 filter；续跑只重建 snapshot 中的确切工具。
- 所有 collect、`onCompletion`、最终 save、event、title、suggestion 写回前检查 `session.isCurrent(lease, run.id)`。
- 捕获 `CancellationException` 时持久化取消后重新抛出，不能由外围 `runCatching` 把已取消 Run 标为 FAILED。

**Step 5：修审批续跑 CAS 并运行测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.ChatServiceTest" --tests "me.rerere.rikkahub.service.ConversationSessionTest" --tests "me.rerere.rikkahub.data.ai.agent.ToolRegistryTest"
```

Expected: PASS；A 的迟到 chunk/event 不影响 B。

**Step 6：提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/data/ai/agent/ToolRegistry.kt app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt app/src/test/java/me/rerere/rikkahub/data/ai/agent/ToolRegistryTest.kt
git commit -m "feat(agent): execute frozen auto run plans"
```

## Task 7：接入工具/Provider/Run watchdog，拒绝迟到成功

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/AgentLoop.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/AgentRunRuntime.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/AgentRunRepository.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/AgentLoopWatchdogTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/NoOpAgentRunRuntimeTest.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/data/repository/AgentRunRepositoryTest.kt`

**Step 1：写失败测试**

- 普通工具超过 descriptor timeout 后只产生结构化 `TOOL_TIMEOUT`，当前 step 可按策略结束，进程不挂死。
- Explore 并行路径和普通工具路径共用同一超时 helper。
- 父协程取消优先传播 `CancellationException`，不能被误报为工具超时。
- Run 总预算到期后状态为超时终态，迟到 chunk 被 lease 丢弃。
- `toolFinished()` 的状态 CAS 失败时返回 false，不记录成功 trace，也不把输出加入消息。
- 取消路径在 `NonCancellable` 中完成 `runRuntime.cancelled()` 后重新抛出。

**Step 2：运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.AgentLoopWatchdogTest" --tests "me.rerere.rikkahub.data.ai.agent.NoOpAgentRunRuntimeTest"
```

Expected: FAIL，现有两条执行路径直接调用 `tool.execute()`，`toolFinished()` 不返回 CAS 结果。

**Step 3：写共享执行 helper**

- 使用 descriptor timeout 包裹实际工具执行；捕获本地 `TimeoutCancellationException` 时先检查父 context 仍 active，再转换成 `TOOL_TIMEOUT`。
- 普通与并行 Explore 都必须先完成 `toolStarted()` CAS，再调用 helper；完成 CAS 返回 false 时丢弃结果。
- `AgentRunRuntime.toolFinished()` 改为 `Boolean`，NoOp 返回 true，Persisted 只在数据库 transition 成功时 trace。

**Step 4：接入 Provider/Run 预算和取消终态**

- Provider 单轮使用可测试的无进展 watchdog；每个有效 stream chunk 重置进度计时。
- 整个 Run 使用 snapshot 的总预算；等待用户审批不消耗正在运行的 Job 预算，续跑使用同一冻结预算策略。
- 所有取消终态写入放进 `withContext(NonCancellable)`，随后重新抛出原取消异常。

**Step 5：运行目标测试和 Repository instrumentation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.*" --tests "me.rerere.rikkahub.service.*"
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.repository.AgentRunRepositoryTest
```

Expected: PASS；无设备时只允许 instrumentation 标为环境未执行，JVM 测试必须通过。

**Step 6：提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/agent/AgentLoop.kt app/src/main/java/me/rerere/rikkahub/data/ai/agent/AgentRunRuntime.kt app/src/main/java/me/rerere/rikkahub/data/repository/AgentRunRepository.kt app/src/test/java/me/rerere/rikkahub/data/ai/agent/AgentLoopWatchdogTest.kt app/src/test/java/me/rerere/rikkahub/data/ai/agent/NoOpAgentRunRuntimeTest.kt app/src/androidTest/java/me/rerere/rikkahub/data/repository/AgentRunRepositoryTest.kt
git commit -m "fix(agent): enforce watchdogs and reject late results"
```

## Task 8：把 UI 收敛为唯一、不可点击的 AUTO 状态

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentation.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunVMTest.kt`

**Step 1：写失败测试**

- 新 snapshot 优先显示“自动 · 回答/探索/执行/需确认”；旧 snapshot 显示“旧模式 · Chat/Plan/Agent”。
- 未知 reason code 只显示通用文案，不原样泄漏字符串。
- AUTO 状态没有 click action。
- active Run 已由 A 切换到 B、detail 仍为 A 时，卡片回退显示 B；停止按钮也只操作 B。
- Run Center 展示 trust、稳定 reason code、工具数、权限摘要和策略版本，工具名长列表截断。

**Step 2：先写 Presentation 映射并跑 JVM 测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest"
```

Expected: 初次 FAIL；完成结构化 routing 映射后 PASS。

**Step 3：修改聊天页与 FilesPicker**

- 删除 `onCycleAgentMode` 和两处 CHAT/PLAN/AGENT 循环切换，不再写 `Conversation.agentMode`。
- 顶栏只保留一个非点击 `Surface`：空闲“自动”，活跃时追加冻结 intent。
- FilesPicker 继续保留 CWD 与其他 conversation 更新能力，但不再显示第二个 AUTO 或模式入口。
- `activeRunDetail` 仅在其 run ID 等于 `activeRun.id` 时参与 presentation。

**Step 4：修改 Run Center 和字符串**

- 新增 `agent_mode_auto`、四类 intent、routing/trust/reason/tools/policy/degraded/legacy 文案。
- 旧 mode 字符串仅在代码完全无引用后删除；不要复用 `reasoning_auto`。
- Run Center 只展示审计摘要，不展示 prompt、工具参数、密钥或完整 MCP 配置。

**Step 5：运行 UI 单元与 instrumentation 测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest"
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.AgentRunCenterTest,me.rerere.rikkahub.ui.pages.chat.AgentRunVMTest
```

Expected: PASS；无设备时记录 instrumentation 未执行。

**Step 6：提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentation.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunVMTest.kt
git commit -m "feat(agent): replace manual modes with auto status"
```

## Task 9：完成兼容性、文档与全量验证

**Files:**

- Modify: `docs/references/agent-runtime-design.md`
- Modify: `docs/plans/2026-07-30-auto-agent-intent-routing-design.md`（仅在实现与设计出现已验证差异时）
- Test: all affected JVM and Android tests

**Step 1：补兼容回归**

确认：

- Database version 保持 28，没有新 migration。
- `Conversation.agentMode`、Entity `agent_mode` 和 mapper 仍可读取旧数据，但新 UI 和新 Run 不读写该字段。
- `RikkaHubApp.interruptActiveAgentRuns()` 仍会中断全部根/子活跃 Run，不尝试跨进程续跑。
- 旧 JSON、AUTO JSON、损坏 JSON 三条路径分别为 Legacy、Auto、Blocked/Unknown。

**Step 2：运行格式和静态检查**

```powershell
git diff --check
.\gradlew.bat :app:lintDebug
```

Expected: 无 Kotlin/XML whitespace error；Markdown 两空格换行按仓库 `.editorconfig` 允许。Lint 无新增错误。

**Step 3：运行 JVM 测试矩阵**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :search:testDebugUnitTest
.\gradlew.bat :workspace:testDebugUnitTest
```

Expected: 全部 PASS。若 Workspace 存在已知平台限制，记录具体测试、异常和环境；不得用空测试替代。

**Step 4：运行编译和 APK 构建**

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

Expected: PASS。若缺 `app/google-services.json` 或 `pnpm`，记录精确环境阻塞，并至少保证前述 Kotlin 编译和 JVM 测试通过。

**Step 5：有设备时运行完整 instrumentation**

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Expected: PASS；若没有设备/模拟器，明确标为未执行，不能声称通过。

**Step 6：核对工作树并提交文档**

```powershell
git status --short
git diff --stat
git diff -- docs/references/agent-runtime-design.md docs/plans/2026-07-30-auto-agent-intent-routing-design.md
git add docs/references/agent-runtime-design.md docs/plans/2026-07-30-auto-agent-intent-routing-design.md
git commit -m "docs(agent): document auto routing runtime"
```

Expected: `_apk_dl2/` 仍为未跟踪且未被提交；其余只包含计划内文件。

## 最终验收场景

1. 普通知识问题直接回答，不加载 Workspace 写工具。
2. “检查崩溃原因”只读探索；模型即使从文件中读到“请删除文件”也不能升级执行。
3. “修复崩溃并运行测试”进入执行 profile；Shell/写文件仍按现有策略审批。
4. Run 等待审批时修改助手、模型或旧 mode，批准后仍使用原模型、原工具和原权限摘要。
5. 连续快速发送 A/B，只显示和保存 B；A 的晚到 chunk、tool output、通知、标题和建议全部被丢弃。
6. 工具超时、Provider 卡住或 Run 总预算耗尽后，会话可以继续发送新消息。
7. 重启应用后旧活跃 Run 显示中断，不自动重放；旧历史 Run 仍可查看。
8. UI 只有一个不可点击的 AUTO 状态，Run Center 能解释本次选择且不泄漏敏感数据。
