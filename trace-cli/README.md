# trace-cli

使用真实 Provider 的 SSE 响应生成 `ai` 模块可回放的 `events.jsonl`。CLI 只记录 SSE 分帧后的
`id`、`event`、`data` 和 `retryMillis`，不会记录请求头或 API Key。

支持的 Provider：

- `openai-responses`
- `openai-chat`
- `claude`
- `google`

## 安装

```bash
cd trace-cli
bun install
cp .env.example .env
```

在 `.env` 中填写需要使用的密钥：

```dotenv
OPENAI_API_KEY=...
ANTHROPIC_API_KEY=...
GEMINI_API_KEY=...
DEEPSEEK_API_KEY=...
```

`.env` 已被子项目的 `.gitignore` 排除。也可以在单条 trace 中用 `apiKeyEnv`
指定其他环境变量名。

## 生成轨迹

直接编辑 `traces.yml` 中需要录制的 Provider、模型和请求体。

查看配置解析后的 case：

```bash
bun run trace -- traces.yml --list
```

检查脱敏后的实际请求，不访问网络：

```bash
bun run trace -- traces.yml --case openai-responses-tool --dry-run
```

生成一条或全部轨迹：

```bash
bun run trace -- traces.yml --case openai-responses-tool
bun run trace -- traces.yml
```

默认不会覆盖已有文件。确认需要重新录制时使用：

```bash
bun run trace -- traces.yml --case openai-responses-tool --force
```

CLI 先写入临时文件，请求和 SSE 解析全部成功后再原子替换目标 `events.jsonl`。

## YAML 格式

```yaml
version: 1
defaults:
  outputRoot: ../ai/src/test/resources/stream-traces/generated
  timeoutMs: 120000
  headers: {}

traces:
  - name: responses-tool
    provider: openai-responses
    model: gpt-5.6
    apiKeyEnv: OPENAI_API_KEY
    # 可选：覆盖 provider 默认认证方式，例如 OpenRouter Anthropic Messages 使用 Bearer。
    auth:
      header: Authorization
      scheme: Bearer
    baseUrl: https://api.openai.com/v1
    # endpoint 可省略，也可以是相对 baseUrl 的路径或完整 URL。
    endpoint: /responses
    # output 可省略；默认写入 outputRoot/provider/name/events.jsonl。
    output: ../ai/src/test/resources/stream-traces/openai-responses/responses-tool/events.jsonl
    headers: {}
    timeoutMs: 120000
    body:
      store: false
      input:
        - role: user
          content: Call the weather tool for Paris.
      tools:
        - type: function
          name: weather
          description: Get weather for a city.
          parameters:
            type: object
            properties:
              city:
                type: string
            required: [city]
            additionalProperties: false
```

CLI 会自动添加各 Provider 所需的认证 Header，并为 OpenAI/Claude 请求强制设置 `stream: true`。
Google 使用 `:streamGenerateContent?alt=sse`，其模型名只用于 URL，不会写入请求体。

生成后仍需人工创建对应的 `expected.json`，再把目录加入 `StreamTraceReplayTest`。
