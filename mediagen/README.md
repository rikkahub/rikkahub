# mediagen 抽象层

`mediagen` 是独立于聊天 `Provider` 的媒体生成抽象层，图像和视频共用同一套模型和供应商接口：
`MediaGenerationProviderSetting`（sealed class，带 `id` / `name`，可序列化保存多份）、无状态的
`MediaGenerationProvider` 和按 setting 类型分发的 `MediaGenerationManager`。

## 厂商与模型

setting 是厂商级的，只保存凭据（`apiKey` / `baseUrl`）和该厂商下的 `models` 列表，同一份配置可以同时提供图像和视频模型。
每个 `MediaGenerationModel` 带 `modelId`（下发给接口的模型名）和 `kind`（`IMAGE` / `VIDEO`）；调用时显式传入模型，
厂商适配器按 `model.kind` 路由到对应接口。

`setting.supportedKinds` 是适配器目前实现的 kind，不随配置保存，供上层决定可以添加哪类模型。传入不在其中的模型时，
`MediaGenerationManager` 直接返回 `UnsupportedOperationException`，不会发出请求。

| 供应商               | 图像                      | 视频             |
|-------------------|-------------------------|----------------|
| OpenAI（及兼容协议的中转） | 生成 + 编辑，同步              | 未实现            |
| 阿里云百炼             | 万相 / 千问，生成 + 编辑，同步      | 万相，异步任务        |
| 火山方舟              | Seedream，生成 + 参考图生图，同步 | Seedance，异步任务 |
| MiniMax           | 未实现                     | H3，异步任务        |

适配器按厂商分包，放在 `provider/providers/{openai,aliyun,volcengine,minimax}` 下，公用的 HTTP 和图片工具留在
`provider/providers`。两种 kind 都实现的厂商，provider 只做路由，图像和视频协议各自放在 `{Vendor}ImageGeneration` /
`{Vendor}VideoGeneration` 里。
给已有厂商补另一种 kind 时，加上对应的类和路由分支并扩充 `supportedKinds`，setting 和公共接口不用改。

## 设计边界

所有供应商都收敛到同一个任务生命周期：

1. `create` 提交请求并得到 `MediaGenerationTask`；
2. 任务未到终态时用 `query` 轮询；
3. 成功后从 `outputs` 取结果。

同步接口（三家的图像）在 `create` 里直接返回终态任务，没有可查询的任务。上层不需要区分两种情况：
`MediaGenerationManager.generate` 提交后跟踪到终态，同步供应商只发出一次，异步供应商按间隔轮询；
`watch` 用于按任务 ID 恢复轮询。两者都是可取消的 Flow。

模块只负责供应商协议适配，不负责持久化任务、下载或落盘结果、把本地素材上传成公网地址或 UI 状态管理。接口返回非 2xx
时抛出 `MediaGenerationApiException`（携带 `provider`、`statusCode`、`code`）；接口正常返回但没有任何产出时，得到的是
带 `error` 的 `FAILED` 任务。

公共模型位于 `model/` 包：

- `MediaGenerationModel`：厂商下的一个模型，带 `modelId`、`kind`、可选的 `displayName` 和用于持久化选择的 `id`；
- `MediaGenerationRequest`：提示词、多模态输入、数量、分辨率、比例、时长、音频、水印等公共字段。`resolution`
  对视频是清晰度档位（`1080P`），对图像是像素尺寸（`1536x1024`）；适配器不支持的字段会直接报错；
- `MediaGenerationInput`：首帧、尾帧、参考图、参考视频、参考音频、文件、网页以及供应商原始输入。视频接口要求公网地址；
  图像接口的 `Image` 也接受本地文件路径；
- `MediaGenerationTask`：统一的排队、运行、成功、失败、取消、过期状态和结果；
- `MediaGenerationOutput`：异步任务给出有时效的 `url`，需要上层及时下载或转存；同步接口内联返回的内容在 `data`
  （已解码的字节，不参与序列化）；
- `extraParameters` / `Raw`：承接模型快速迭代产生的供应商专属字段，避免频繁修改公共 API。

## 图像

三家的图像接口都是同步的，生成一张高质量图片可能需要数分钟，传入的 `OkHttpClient` 需要配置足够长的读超时。请求里带
`MediaGenerationInput.Image` 就是编辑 / 参考图生图，否则是文生图；图像接口不区分 `ImageRole`。

### 字段映射

| 公共字段                | OpenAI        | 火山 Seedream           | 阿里万相 / 千问               |
|---------------------|---------------|-----------------------|-------------------------|
| `count`             | `n`           | 只接受 1，组图走 `extraParameters` | `n`                     |
| `resolution`        | `size`        | `size`（`2K` 或 `2048x2048`） | `size`（`2K`，像素尺寸转成 `1536*1024`） |
| `watermark`         | 不支持           | `watermark`           | `watermark`             |
| `seed`              | 不支持           | 不支持                   | `seed`                  |
| `promptEnhancement` | 不支持           | 不支持，走 `extraParameters` | `prompt_extend`         |
| 图片输入                | 本地文件，multipart | 公网地址 / data URI / 本地文件 | 公网地址 / data URI / 本地文件  |
| 结果                  | 内联字节或 url     | 24 小时有效的 url，或内联字节    | 24 小时有效的 url           |

`aspectRatio`、`durationSeconds`、`generateAudio`、`callbackUrl` 三家的图像接口都不支持，传了会直接报错。

### OpenAI

- 请求里没有输入时走 `POST {baseUrl}/images/generations`（JSON）；带 `MediaGenerationInput.Image` 时走
  `POST {baseUrl}/images/edits`（multipart）。编辑要求 `url` 是本地文件路径，单图字段名为 `image`，多图为
  `image[]`，仅接受 png / jpg / jpeg / webp；
- `resolution` 映射为 `size`，为空或 `auto` 时不下发；Grok（`x.ai` 域名或模型名含 `grok`）不接受 `size`，同样不下发；
- `quality`、`background`、`output_format` 等走 `extraParameters`；
- 响应里的 `b64_json` 解码后放进 `output.data`；只返回 `url` 时（dall-e 系列和部分中转）原样放进 `output.url`。

### 火山 Seedream

- `POST {baseUrl}/images/generations`（JSON）。参考图放在 `image` 字段，单张是字符串，多张是数组；本地文件会被读出并编码成
  `data:image/<格式>;base64,...`；
- 接口没有 `n`：每次请求生成一张，组图需要在 `extraParameters` 里传 `sequential_image_generation` 和
  `sequential_image_generation_options`；`output_format`、`response_format`、`optimize_prompt_options`、
  `layer_decomposition` 等同样走 `extraParameters`；
- 默认返回 `url`；`response_format` 设为 `b64_json` 时解码后放进 `output.data`；
- 组图里单张失败的项会被跳过，逐图信息（含图层拆分的 `z_index`、`bounding_box`）保留在 `task.metadata`。

### 阿里万相 / 千问

- 百炼按业务空间分配域名，默认 `baseUrl` 是 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1`，其中的
  `{WorkspaceId}` 会被替换成 setting 的 `workspaceId`（图像和视频共用）。换地域时改 `baseUrl` 里的地域段；填不含占位符的
  地址（如旧的 `https://dashscope.aliyuncs.com/api/v1`）则原样使用，不需要 `workspaceId`；
- `POST {baseUrl}/services/aigc/multimodal-generation/generation`（JSON，同步）。提示词和图片放在
  `input.messages[0].content` 里，其余参数在 `parameters` 下；
- 适用于 `wan2.7-image` / `wan2.6-image` 和 `qwen-image` 系列。只提供旧版异步接口的模型（如 `wan2.5-t2i-preview`）不支持；
- `negative_prompt`、`thinking_mode`、`enable_sequential` 等走 `extraParameters`；
- 响应里的 `usage.size`（`1488*704`）或 `width` / `height` 统一成 `1488x704` 放进 `output.resolution`。

## 视频

### 能力差异

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
val manager = MediaGenerationManager(okHttpClient)

// 图像：同步返回，Flow 只发出一次终态任务
val openAI = MediaGenerationProviderSetting.OpenAI(apiKey = apiKey)
val image = manager.generate(
    setting = openAI,
    model = openAI.models.first { it.kind == MediaKind.IMAGE },
    request = MediaGenerationRequest(
        prompt = "把背景换成雪山",
        inputs = listOf(MediaGenerationInput.Image(file.absolutePath)), // 不带输入即文生图
        resolution = "1536x1024",
        extraParameters = buildJsonObject { put("quality", "high") },
    ),
).last()
image.outputs.forEach { output -> /* output.data ?: 下载 output.url */ }

// 视频：异步任务，按间隔轮询到终态
val miniMax = MediaGenerationProviderSetting.MiniMax(apiKey = apiKey)
val videoModel = MediaGenerationModel(modelId = "MiniMax-H3", kind = MediaKind.VIDEO)
manager.generate(
    setting = miniMax,
    model = videoModel,
    request = MediaGenerationRequest(
        prompt = "雨夜城市中，一辆复古跑车缓慢驶过霓虹街道",
        resolution = "2K",
        aspectRatio = "16:9",
        durationSeconds = 5,
    ),
).collect { task ->
    // 将 task 持久化或更新 UI；terminal 状态后 Flow 自动结束。
    // 应用重启后可用 manager.watch(miniMax, videoModel, task.id) 继续轮询。
}
```

## 官方文档

- [OpenAI Images API](https://platform.openai.com/docs/api-reference/images)
- [阿里云百炼 Base URL 总览](https://help.aliyun.com/zh/model-studio/base-url)
- [阿里云百炼万相图像生成与编辑 2.7](https://help.aliyun.com/zh/model-studio/wan-image-generation-and-editing-api-reference)
- [阿里云百炼千问文生图](https://help.aliyun.com/zh/model-studio/qwen-image-api)
- [阿里云百炼万相 3.0 视频](https://help.aliyun.com/zh/model-studio/wan3-video-generation-api-reference)
- [火山方舟图片生成 API](https://www.volcengine.com/docs/82379/1541523)
- [火山方舟视频生成 API](https://www.volcengine.com/docs/82379/1520757)
- [MiniMax H3 创建视频任务](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-create)
- [MiniMax H3 查询任务](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-query)
