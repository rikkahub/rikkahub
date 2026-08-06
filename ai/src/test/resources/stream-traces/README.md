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

快照会保留工具调用 ID，并将签名、加密思考等不稳定元数据记录为存在性标记；思考文本、
工具名称与参数、token usage 等内容也会完整保留。
