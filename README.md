# RikkaHub Mod (Master)

这是基于 RikkaHub 上游持续追更的深度魔改版本。  
`master` 是本仓库唯一对外主线（默认展示、日常开发、发版都在这条分支）。

## 项目定位

- 仍然是原生 Android LLM 客户端（Jetpack Compose + Kotlin）
- 保留上游 2.x 迭代能力
- 在此基础上加入面向进阶用户的本地增强能力（容器、工作流、沙箱、自动化等）

## 与上游版本的主要差异

本分支不是“轻度换皮”，而是深度改造分支，核心差异如下：

- 包名与配置策略已调整，便于与上游版本共存安装
- Firebase 相关路径已禁用
- 新增容器运行时能力（PRoot 方向）
- 新增 Chaquopy 工具链能力
- 新增沙箱文件管理能力（含聊天快捷入口）
- 新增工作流能力（工具栏开关、侧边栏入口、阶段切换）
- 新增模型提供商 API Key 轮询能力
- 增强搜索能力（含本地/内置搜索方向整合）

## 当前版本特性（Master）

- 多模型/多提供商聊天
- MCP 工具接入
- 多模态输入（图片/文档等）
- 消息分支、上下文压缩、清空上下文
- 工作流（PLAN / EXECUTE / REVIEW）
- 容器与沙箱协同
- 本地工具体系（时间、剪贴板、文件、Chaquopy、容器、TODO、SubAgent）
- Web 服务与移动端本地化增强

## 构建与运行

### 环境建议

- Android Studio（最新版稳定版）
- JDK 17
- Android SDK / NDK（按 `app/build.gradle.kts` 要求）

### 常用命令

```bash
# Debug 构建（跳过测试）
./gradlew :app:assembleDebug -x test

# Release 构建
./gradlew :app:assembleRelease
```

生成产物通常位于：

- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`

## 分支约定（本仓库）

- `master`：唯一对外主线（你现在看到的就是它）
- `main`：上游干净镜像分支（只同步 upstream，不做魔改）
- `mod-1.9`：历史冻结基线（仅回溯）
- `port-*`：每次追更时的临时施工分支

追更流程：`main` 同步上游 -> 新建 `port-*` -> 合入 `master`。

## 说明

- 本仓库为个人维护的改造线，不等同于上游官方发布。
- 如需对比上游变更，请直接查看提交记录与分支差异。

## 致谢

- 感谢 RikkaHub 上游作者与社区持续迭代。
- 本分支所有“深度魔改”能力均建立在上游优秀基础之上。
