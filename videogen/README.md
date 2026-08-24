# videogen 抽象层

`videogen` 统一阿里云百炼万相、火山方舟 Seedance 和 MiniMax H3 的异步视频生成协议。

## 设计边界

三家接口都遵循相同的任务生命周期：

1. 提交生成请求并获得任务 ID；
2. 轮询任务状态；
3. 成功后取得有时效的视频 URL，并由上层及时下载或转存。

模块只负责供应商协议适配，不负责持久化任务、下载视频、上传本地素材或 UI 状态管理。

公共模型位于 `model/VideoGeneration.kt`：

- `VideoGenerationRequest`：提示词、多模态输入、分辨率、比例、时长、音频、水印等公共字段；
- `VideoGenerationInput`：首帧、尾帧、参考图、参考视频、参考音频、文件、网页以及供应商原始输入；
- `VideoGenerationTask`：统一的排队、运行、成功、失败、取消、过期状态和结果；
- `extraParameters` / `Raw`：承接模型快速迭代产生的供应商专属字段，避免频繁修改公共 API。

`VideoGenerationProvider` 只暴露 `create` 和 `query`。`VideoGenerationManager.watch` 提供可取消的轮询
Flow，UI 或任务仓库可直接订阅。

## 能力差异

| 能力        | 阿里万相 3.0        | 火山 Seedance 2.0 | MiniMax H3     |
|-----------|-----------------|-----------------|----------------|
| 文生视频      | 支持              | 支持              | 支持，且 prompt 必填 |
| 首/尾帧      | 支持              | 支持              | 支持             |
| 参考图/视频/音频 | 支持              | 支持              | 支持             |
| 文件/网页输入   | 支持              | 不支持             | 不支持            |
| 智能时长 `-1` | 支持              | 部分模型支持          | 不支持            |
| 请求级回调 URL | 不支持，使用账号级异步回调配置 | 支持              | 支持             |
| 有声输出开关    | 支持              | 部分模型支持          | API 未暴露        |

不同模型的时长、分辨率、素材数量及格式限制变化较快，因此公共层不硬编码模型能力表；适配器只做协议级校验，服务端仍是模型参数合法性的最终来源。

## 使用示例

```kotlin
val manager = VideoGenerationManager(okHttpClient)
val setting = VideoGenerationProviderSetting.MiniMax(apiKey = apiKey)

val submitted = manager.create(
    setting = setting,
    request = VideoGenerationRequest(
        prompt = "雨夜城市中，一辆复古跑车缓慢驶过霓虹街道",
        resolution = "2K",
        aspectRatio = "16:9",
        durationSeconds = 5,
    ),
).getOrThrow()

manager.watch(setting, submitted.id).collect { task ->
    // 将 task 持久化或更新 UI；terminal 状态后 Flow 自动结束。
}
```

## 官方文档

- [阿里云百炼万相 3.0](https://help.aliyun.com/zh/model-studio/wan3-video-generation-api-reference)
- [火山方舟视频生成 API](https://www.volcengine.com/docs/82379/1520757)
- [MiniMax H3 创建视频任务](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-create)
- [MiniMax H3 查询任务](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-query)
