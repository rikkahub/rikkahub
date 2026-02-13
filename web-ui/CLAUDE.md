# CLAUDE.md

This file provides guidance to AI agents when working with code in this repository.

## Project Overview

web-ui 是 RikkaHub 项目的嵌入式 Web 前端，基于 React Router 7 的单页应用（SPA）。构建产物通过 `copy.ts` 脚本复制到 `../web/src/main/resources/static` 目录，由 Kotlin 后端的 Ktor 服务器提供静态文件服务。

## Technology Stack

- **React Router 7** (7.12.0): 文件路由系统 + Vite 构建 (SPA 模式: `ssr: false`)
- **React 19** (19.2.4): UI 框架
- **TypeScript** (5.9.2): 严格类型检查，与 Kotlin 后端类型完全对齐
- **Tailwind CSS v4**: 原子化 CSS 框架 + CSS 变量主题系统
- **shadcn/ui** (New York style): 组件库，基于 Radix UI
- **Zustand** (5.0.11): 轻量级状态管理（组合 slices 模式）
- **ky** (1.14.3): 基于 fetch 的 HTTP 客户端
- **i18next + react-i18next**: 国际化方案（zh-CN / en-US）
- **Bun**: 包管理器和运行时

## Development Commands

```bash
# 开发服务器 (HMR + API 代理到 localhost:8080)
bun run dev

# 生产构建 (构建 + 复制到后端)
bun run build

# 类型检查和生成
bun run typecheck

# 代码格式化
bun run fmt

# 检查格式
bun run fmt:check

# 生产服务器 (运行 build/server/index.js)
bun run start
```

## Architecture

### Directory Structure

```
app/
├── routes/                   # React Router 7 文件路由
│   ├── home.tsx             # / 路由 (re-exports conversations.tsx)
│   ├── c.$id.tsx            # /c/:id 路由 (re-exports conversations.tsx)
│   └── conversations.tsx     # 主对话页面实现 (650+ 行)
│
├── components/               # 组件库
│   ├── ui/                   # shadcn/ui 基础组件 (36+ 组件)
│   ├── message/              # 消息相关组件 (chat-message, parts/)
│   ├── markdown/             # Markdown 渲染 (markdown.tsx + code-block.tsx)
│   ├── input/                # 输入组件 (chat-input, model-list, pickers)
│   ├── workbench/            # 代码执行工作台
│   ├── extended/             # 扩展组件 (conversation, infinite-scroll-area)
│   ├── conversation-sidebar.tsx      # 对话列表侧边栏
│   ├── conversation-search-button.tsx
│   ├── conversation-quick-jump.tsx
│   ├── custom-theme-dialog.tsx
│   └── theme-provider.tsx
│
├── stores/                   # Zustand 状态管理 (组合 slices)
│   ├── app-store.ts          # 主 store (组合所有 slices)
│   ├── settings.ts           # 导出 useSettingsStore
│   ├── chat-input.ts         # 导出 useChatInputStore
│   ├── slices/
│   │   ├── types.ts          # Store 类型定义
│   │   ├── settings-slice.ts # 设置 slice (从 SSE 更新)
│   │   └── chat-input-slice.ts # 聊天输入 slice (多对话草稿)
│   └── hooks/
│       └── use-settings-subscription.ts # SSE 订阅设置流
│
├── hooks/                    # 自定义 React Hooks
│   ├── use-conversation-list.ts  # 对话列表管理 (分页/实时更新)
│   ├── use-current-assistant.ts  # 当前助手信息
│   ├── use-current-model.ts      # 当前模型信息
│   ├── use-mobile.ts             # 移动设备检测
│   └── use-picker-popover.ts     # Picker 气泡管理
│
├── services/                 # API 服务层
│   └── api.ts                # ky HTTP 客户端 + SSE 实现 (176 行)
│
├── types/                    # TypeScript 类型 (与 Kotlin 对齐)
│   ├── core.ts               # MessageRole, TokenUsage
│   ├── parts.ts              # UIMessagePart (union of 7 part types)
│   ├── annotations.ts        # UIMessageAnnotation (UrlCitationAnnotation)
│   ├── message.ts            # UIMessage
│   ├── conversation.ts       # MessageNode, Conversation
│   ├── dto.ts                # API DTOs (ConversationDto, MessageDto, etc.)
│   ├── settings.ts           # Settings, DisplaySetting, AssistantProfile, etc.
│   ├── helpers.ts            # 类型守卫和工具函数
│   └── index.ts              # 统一导出
│
├── lib/                      # 工具函数
│   ├── utils.ts              # cn() - Tailwind + clsx
│   ├── display.ts            # 显示名称格式化
│   ├── files.ts              # 文件 URL 转换
│   └── error.ts              # 错误处理
│
├── locales/                  # 国际化语言文件（命名空间组织）
│   ├── zh-CN/                # 简体中文（默认语言）
│   │   ├── common.json       # 通用 UI 翻译（侧边栏、主题等）
│   │   ├── input.json        # 输入相关翻译（聊天、模型选择）
│   │   ├── markdown.json     # Markdown 渲染翻译
│   │   └── message.json      # 消息显示翻译（消息部分、工具）
│   └── en-US/                # 英文
│       ├── common.json
│       ├── input.json
│       ├── markdown.json
│       └── message.json
│
├── assets/                   # 静态资源
├── i18n.ts                   # i18next 配置
├── root.tsx                  # 根布局 (调用 useSettingsSubscription)
├── routes.ts                 # 路由配置 (type-safe)
└── app.css                   # 全局样式 (Tailwind + CSS 变量)
```

### Key Concepts

#### Type System (与 Kotlin 后端完全对齐)

所有 `app/types/` 下的类型与 Kotlin 后端严格对应：

| TypeScript 类型 | Kotlin 类型 | 后端位置 |
|----------------|------------|---------|
| `MessageRole` | `MessageRole` | `ai/src/main/java/me/rerere/ai/core/MessageRole.kt` |
| `TokenUsage` | `Usage` | `ai/src/main/java/me/rerere/ai/core/Usage.kt` |
| `UIMessagePart` | `UIMessagePart` | `ai/src/main/java/me/rerere/ai/ui/Message.kt` |
| `UIMessage` | `UIMessage` | `ai/src/main/java/me/rerere/ai/ui/Message.kt` |
| `MessageNode` | `MessageNode` | `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt` |
| `Conversation` | `Conversation` | `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt` |
| `ConversationDto` | `ConversationDto` | `app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt` |
| `Settings` | `Settings` | `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt` |

**类型更新时需同步修改前后端！**

#### Message Parts (消息部分)

消息由多个 `UIMessagePart` 组成（联合类型），支持混合内容：

```typescript
type UIMessagePart =
  | TextPart         // { type: "text", text: string }
  | ImagePart        // { type: "image", url: string }
  | VideoPart        // { type: "video", url: string }
  | AudioPart        // { type: "audio", url: string }
  | DocumentPart     // { type: "document", url, fileName, mime }
  | ReasoningPart    // { type: "reasoning", reasoning, steps[] }
  | ToolPart         // { type: "tool", toolCallId, input, output, approvalState }
```

每个 part 由独立的组件渲染 (`app/components/message/parts/`)。

#### Message Branching (消息分支)

`MessageNode` 支持对话分支，每个节点包含多个可选消息：

```typescript
interface MessageNode {
  id: string;
  messages: UIMessage[];     // 多个可选分支
  selectIndex: number;       // 当前选中的分支
}
```

用户可以：
- 重新生成响应创建新分支
- 在不同分支间切换
- 编辑消息创建分叉

#### State Management (Zustand Slices)

采用组合 slices 模式，所有 slices 共享同一个 store：

```typescript
// app-store.ts
const useAppStore = create<AppStoreState>()((...args) => ({
  ...createSettingsSlice(...args),      // 全局设置
  ...createChatInputSlice(...args),     // 聊天输入
}));

// 导出特定的 store hook
export const useSettingsStore = useAppStore;
export const useChatInputStore = useAppStore;
```

**Settings Slice**:
- 从后端 SSE 流 (`/api/settings/stream`) 实时更新
- 包含助手、模型、提供商、显示设置等全局配置
- 在 `root.tsx` 中通过 `useSettingsSubscription` 订阅

**Chat Input Slice**:
- 为每个对话维护独立的输入草稿
- 支持文本 + 多媒体附件（图片、视频、音频、文档）
- 编辑消息时保存源部分用于对比

#### API Client (app/services/api.ts)

基于 `ky` 的 HTTP 客户端，支持 REST API 和 SSE 流：

```typescript
// REST API
await api.get<T>(url, options)
await api.post<T>(url, data, options)
await api.postMultipart<T>(url, formData)  // 文件上传
await api.put<T>(url, data)
await api.patch<T>(url, data)
await api.delete<T>(url, options)

// SSE 流 (手动实现，支持事件类型和多行数据)
sse<T>(url, {
  onMessage: ({ data, event, id }) => { ... },
  onError: (error) => { ... },
  onOpen: () => { ... },
  onClose: () => { ... },
}, { signal: abortController.signal })
```

**配置**:
- 默认前缀: `/api` (开发时代理到 `http://localhost:8080`)
- 超时: 30 秒
- 自动错误处理: 转换为 `ApiError` 类

**开发时代理** (`vite.config.ts`):
```typescript
server: {
  proxy: {
    "/api": {
      target: "http://localhost:8080",
      changeOrigin: true,
    },
  },
}
```

#### Routing (React Router 7)

```typescript
// app/routes.ts - 类型安全的路由配置
[
  index("routes/home.tsx"),           // / 路由
  route("c/:id", "routes/c.$id.tsx")  // /c/:id 路由
]
```

**特点**:
- SPA 模式 (`react-router.config.ts: ssr: false`)
- 文件路由自动类型生成 (`.react-router/types/`)
- 实际 UI 在 `routes/conversations.tsx` 实现，其他路由文件 re-export
- 错误边界和加载占位符在 `root.tsx`

#### Internationalization (i18next)

**支持语言**: zh-CN (默认), en-US

**命名空间组织** (`app/i18n.ts`):
翻译文件按功能模块拆分为多个命名空间,避免单一文件过大:
- `common`: 通用 UI 翻译(侧边栏、主题、快捷跳转等)
- `input`: 输入相关翻译(聊天输入、模型选择、文件选择器等)
- `markdown`: Markdown 渲染翻译(代码块、复制按钮等)
- `message`: 消息显示翻译(消息部分、工具调用、推理步骤等)

**配置** (`app/i18n.ts`):
```typescript
// 语言检测优先级: localStorage > 浏览器语言 > 默认中文
const fromStorage = window.localStorage.getItem("lang");
const browserLanguage = window.navigator.language;
const initialLanguage = fromStorage || (browserLanguage.startsWith("zh") ? "zh-CN" : "en-US");

// 命名空间配置
i18n.use(initReactI18next).init({
  resources: {
    "zh-CN": {
      common: zhCNCommon,
      input: zhCNInput,
      markdown: zhCNMarkdown,
      message: zhCNMessage,
    },
    "en-US": { /* ... */ },
  },
  defaultNS: "common",  // 默认命名空间
  ns: ["common", "input", "markdown", "message"],
});
```

**翻译文件结构**:
```
app/locales/
├── zh-CN/
│   ├── common.json     # 通用翻译
│   ├── input.json      # 输入相关
│   ├── markdown.json   # Markdown 渲染
│   └── message.json    # 消息显示
└── en-US/
    ├── common.json
    ├── input.json
    ├── markdown.json
    └── message.json
```

**使用方式**:
```typescript
import { useTranslation } from "react-i18next";

function MyComponent() {
  // 默认命名空间 (common)
  const { t } = useTranslation();
  return <div>{t("chat.send_hint_enter")}</div>;

  // 指定命名空间
  const { t: tInput } = useTranslation("input");
  return <div>{tInput("model_list.title")}</div>;

  // 使用命名空间前缀 (推荐)
  return <div>{t("input:model_list.title")}</div>;
}
```

**添加新翻译键**:
1. 确定合适的命名空间 (common/input/markdown/message)
2. 在对应的 `zh-CN/*.json` 和 `en-US/*.json` 中添加键值对
3. 使用 `t("namespace:key")` 或 `t("key")` (默认命名空间) 访问

## Build Process

构建分为两个阶段：

### 1. React Router Build

```bash
react-router build
# 输出:
#  - build/client/  → 静态资源 (HTML + JS + CSS)
#  - build/server/  → SSR 服务器代码 (SPA 模式下不使用)
```

### 2. Copy to Backend

```bash
bun run copy.ts
# 将 build/client/ 复制到 ../web/src/main/resources/static/
# Kotlin 后端的 Ktor 服务器从该目录提供静态文件服务
```

**完整构建命令**: `bun run build` (执行上述两步)

## Development Guidelines

### Component Development

**shadcn/ui 组件**:
- 从 `~/components/ui/` 导入基础组件
- 样式: New York 风格 (`components.json: style: "new-york"`)
- 图标: `import { IconName } from "lucide-react"`
- 路径别名: `~` → `app/` 目录

**组件模式**:
```typescript
import type { ComponentProps } from "react";

interface MyComponentProps extends ComponentProps<"div"> {
  value: string;
  onChange: (value: string) => void;
}

export function MyComponent({ value, onChange, ...props }: MyComponentProps) {
  return <div {...props}>{value}</div>;
}
```

### State Management Patterns

**全局状态 (Zustand)**:
```typescript
// 读取状态
const settings = useSettingsStore((state) => state.settings);
const currentModelId = useSettingsStore((state) => state.settings?.currentModelId);

// 更新状态
const setSettings = useSettingsStore((state) => state.setSettings);
const setText = useChatInputStore((state) => state.setText);

// 使用选择器避免不必要的重新渲染
const currentAssistant = useSettingsStore(
  (state) => state.settings?.assistants.find(a => a.id === state.settings?.currentAssistantId)
);
```

**本地状态**:
```typescript
// UI 特定的状态使用 useState
const [isOpen, setIsOpen] = useState(false);
const [selectedIndex, setSelectedIndex] = useState(0);
```

### Markdown Rendering

`app/components/markdown/markdown.tsx` 提供增强的 Markdown 渲染：

**特性**:
- **LaTeX 数学公式**: 内联 `\(...\)` 和块 `\[...\]` (转换为 `$...$` 和 `$$...$$`)
- **GFM 支持**: 表格、删除线、任务列表等
- **代码高亮**: 基于 Shiki，带复制按钮
- **`<think>` 标签**: 转换为引用块样式
- **引用链接**: `[citation,domain](id)` 格式处理
- **主题适配**: 亮色/暗色主题自动切换

**预处理流程**:
1. 查找所有代码块位置（避免在代码块内替换）
2. 替换 LaTeX 语法和 `<think>` 标签
3. 传递给 `react-markdown` + rehype 插件

### Message Component System

消息渲染采用分发器模式：

```
ChatMessage (容器)
  └── MessageParts (遍历 parts 数组)
      └── MessagePart (分发器 - 根据 part.type)
          ├── TextPart
          ├── ImagePart
          ├── VideoPart
          ├── AudioPart
          ├── DocumentPart
          ├── ReasoningPart (支持展开推理步骤)
          └── ToolPart (支持工具调用展示和批准)
```

**添加新 Part 类型**:
1. 在 `app/types/parts.ts` 添加类型定义
2. 在 Kotlin 后端同步添加 (`ai/.../ui/Message.kt`)
3. 在 `app/components/message/parts/` 创建组件
4. 在 `app/components/message/message-part.tsx` 添加分发逻辑
5. 在 `app/types/helpers.ts` 添加类型守卫

### File URL Handling

`app/lib/files.ts` 提供 URL 转换逻辑：

```typescript
resolveFileUrl(url: string): string
// 处理:
// - data: URL (base64) → 直接返回
// - http/https → 直接返回
// - file:// (Android) → /api/files/path/...
// - 相对路径 → /api/files/path/...
```

在 `ImagePart`, `VideoPart`, `AudioPart`, `DocumentPart` 中使用该函数解析 URL。

### Type Safety

**严格类型检查**:
```bash
bun run typecheck  # React Router 类型生成 + tsc 检查
```

**与 Kotlin 同步**:
- 修改 `app/types/` 下任何类型时，必须同步更新 Kotlin 后端
- 参考上方的类型映射表确定对应的 Kotlin 文件
- 运行 `bun run typecheck` 确保前端类型正确

### Data Flow and Real-time Communication

#### 应用启动流程

```
root.tsx 渲染
  ↓
useSettingsSubscription() 订阅 /api/settings/stream (SSE)
  ↓
后端推送 Settings 对象
  ↓
useSettingsStore.setSettings() 更新全局状态
  ↓
所有组件响应式更新 (助手、模型、提供商等)
```

#### 对话加载流程

```
用户选择/创建对话
  ↓
GET /api/conversations/:id (获取初始快照)
  ↓
返回 ConversationDto (完整消息树)
  ↓
建立 SSE 连接: GET /api/conversations/:id/stream
  ↓
后端推送事件:
  - ConversationSnapshotEventDto (大更新 - 完整快照)
  - ConversationNodeUpdateEventDto (增量更新 - 节点变化)
  ↓
UI 实时渲染消息和生成进度
```

#### 消息发送流程

```
用户点击发送
  ↓
useChatInputStore.getSubmitParts(conversationId)  // 构建消息部分
  ↓
POST /api/conversations/:id/send
  body: { parts: UIMessagePart[] }
  ↓
后端处理并启动生成
  ↓
SSE 流推送:
  - node_update 事件 (每个 token 或部分)
  - conversation.isGenerating = true → false
  ↓
UI 实时显示流式响应
  ↓
生成完成后 useChatInputStore.clearDraft(conversationId)
```

### Custom Hooks Patterns

#### useConversationList

对话列表管理 hook，支持分页和实时更新：

```typescript
const {
  conversations,      // 对话列表
  activeId,           // 当前选中的对话 ID
  setActiveId,        // 设置当前对话
  loading,            // 加载状态
  error,              // 错误信息
  hasMore,            // 是否有更多
  loadMore,           // 加载更多
  refreshList,        // 刷新列表
  updateConversationSummary,  // 增量更新
} = useConversationList({
  currentAssistantId,
  routeId,            // 当前路由的对话 ID
  autoSelectFirst: true,
  pageSize: 30,
});
```

**特点**:
- 自动监听助手切换并刷新
- 支持无限滚动分页
- SSE 事件驱动的增量更新
- 排序: 已固定对话在前，其余按更新时间降序

#### useCurrentAssistant / useCurrentModel

从 Settings store 提取当前助手和模型信息：

```typescript
const { currentAssistant, currentAssistantId } = useCurrentAssistant();
const { currentModel, currentProvider } = useCurrentModel();
```

## Integration with Kotlin Backend

### Static File Serving

```
构建产物流向:
web-ui/build/client/
├── index.html
├── assets/*.js
└── assets/*.css
    ↓ (copy.ts)
../web/src/main/resources/static/
    ↓ (Ktor 静态文件路由)
用户访问 http://localhost:8080/
```

### API Endpoints

所有 API 端点定义在 `web` 模块的 Kotlin 代码中：
- `GET /api/settings/stream` - 设置 SSE 流
- `GET /api/conversations` - 对话列表
- `GET /api/conversations/:id` - 获取对话
- `GET /api/conversations/:id/stream` - 对话 SSE 流
- `POST /api/conversations/:id/send` - 发送消息
- `POST /api/files/upload` - 文件上传
- `GET /api/files/path/*` - 文件访问

### Type Synchronization Checklist

修改类型时的检查清单：

- [ ] 更新 TypeScript 类型 (`app/types/`)
- [ ] 更新对应的 Kotlin 类型（参考上方类型映射表）
- [ ] 运行 `bun run typecheck` 确保前端类型正确
- [ ] 在 Kotlin 端运行类型检查
- [ ] 测试前后端数据序列化/反序列化
- [ ] 更新相关组件的类型守卫 (`app/types/helpers.ts`)

## Performance Optimization

- **代码分割**: React Router 7 自动按路由分割
- **Tree Shaking**: Tailwind v4 + Vite 自动移除未使用的样式和代码
- **选择性订阅**: Zustand store 使用选择器避免不必要的重新渲染
- **虚拟滚动**: 对话列表使用 `react-infinite-scroll-component`
- **SSE 流式更新**: 减少 API 轮询，实时推送数据

## Troubleshooting

### 开发服务器启动失败

检查端口 5173 是否被占用：
```bash
lsof -ti:5173 | xargs kill -9  # 杀掉占用进程
bun run dev
```

### API 请求失败 (开发环境)

确保 Kotlin 后端在 8080 端口运行：
```bash
# 在 Kotlin 项目目录
./gradlew :web:run
```

### 类型错误

运行类型生成和检查：
```bash
bun run typecheck
# 检查 .react-router/types/ 下生成的类型
```

### 构建失败

清理缓存和重新安装依赖：
```bash
rm -rf node_modules .react-router build
bun install
bun run build
```
