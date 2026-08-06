# Stream trace fixtures

每个目录包含两个文件：

- `events.jsonl`：每行一个序列化后的 `SseEvent`，记录在 HTTP 客户端完成 SSE 分帧之后、Provider
  解码之前。不要记录 Authorization 等请求头。
- `expected.json`：`StreamChunkDecoder` 与 `StreamChunkHandler` 处理完成后的稳定语义快照。
  时间戳、随机消息 ID 等易变字段不进入快照。

新增或更新轨迹时，应保留 Provider 返回的 `id`、`event` 和 `data`，首次提交前人工审阅
`expected.json`。常规单元测试只离线回放这些文件，不访问网络。

录制新轨迹后，可在 `ai` 模块目录运行以下命令重新生成语义快照：

```bash
UPDATE_STREAM_TRACE_SNAPSHOTS=true ../gradlew testDebugUnitTest \
  --tests me.rerere.ai.provider.stream.StreamTraceReplayTest
```

快照会保留工具调用 ID、完整 metadata、思考文本、工具名称与参数及 token usage。图片数据保留在
`events.jsonl`，快照只记录 MIME 类型、解码后的字节数和 SHA-256，避免重复存储大段 Base64。
Gemini 图像轨迹还会逐字比较 Provider 原始签名与解码后 metadata，验证签名在流式转换过程中
未被修改。
