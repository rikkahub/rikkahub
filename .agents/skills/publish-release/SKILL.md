---
name: publish-release
description: Use this skill when users request to publish an release update.
---

# Publish Release

请基于git log生成更新日志，然后创建一个新的release

## 更新日志

- 查看从上一次release tag到当前commit的修改
- 总结更新内容并生成更新日志, BUG修复和UI调整尽可能合并，确保总更新日志条目数量不超过 10条
- 日志中避免出现技术名词
- 每条只写一句话，说清核心功能即可；不要附带同一功能的次要行为、交互细节或提示类改动（如「多张图片左右滑动切换」「为空时显示提示并可跳转设置」），这些直接省略
- 功能条目中文不超过 20 个字，英文同样简短；合并的 BUG 修复/UI 调整条目可以例外，但只列问题本身

使用以下格式:

```markdown
更新内容:

- xxx
- xxx

Updates:

- xxx
- xxx
```

(双语更新日志)

更新日志生成完后，请求用户确认更新日志是否合理，等到用户确认可以发布后，创建release

## 发布

使用github cli创建release, 并上传 github 渠道的 release apk（`./gradlew assembleGithubRelease` 输出在 app/build/outputs/apk/github/release/）

- 仅上传arm64版本, 上传前重命名apk，添加版本号到文件名内
- 同时上传 R8 混淆映射 `app/build/outputs/mapping/githubRelease/mapping.txt`，重命名为 `mapping-<版本号>.txt`，用于还原用户反馈的崩溃堆栈（`retrace mapping-<版本号>.txt stacktrace.txt`）
- release标题使用版本号, 描述使用更新日志
- tag名字使用版本号，不带v
