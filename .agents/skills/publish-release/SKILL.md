---
name: publish-release
description: Use this skill when users request to publish an release update.
---

# Publish Release

请基于git log生成更新日志，然后打 tag 触发 CI 构建发布。

构建和上传由 `.github/workflows/release.yml` 完成：推送 `x.y.z` 格式的 tag（不带 v）后，CI 会构建 GitHub 渠道 APK 和 Play 渠道 AAB，
建一个附带 `rikkahub-<版本号>-arm64-v8a.apk` 和 `mapping-<版本号>.zip` 的**草稿** Release，并把 AAB 以草稿状态上传到 Google Play。
本地不需要构建或上传 APK。

仓库配置了多个 remote，所有 `gh` 命令都要带 `-R rikkahub/rikkahub`，git 推送用 `origin`。

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

更新日志生成完后，请求用户确认更新日志是否合理，等到用户确认后再继续。

## 流程

1. **检查版本号**：读取 `app/build.gradle.kts` 里的 `versionName` 和 `versionCode`。`versionName` 不能和已有 tag 重复，
   `versionCode` 必须比上一个版本大（否则 Play 会拒收）。不满足就请用户先升版本号，不要自作主张修改。
2. **检查代码已推送**：工作区干净，且当前 HEAD 已经在 `origin/master` 上（`git fetch origin` 后 `git status` 不显示 ahead）。
   tag 打在未推送的提交上，CI 构建出来的就不是用户以为的那份代码。
3. **生成更新日志**，和用户探讨确认。
4. **打 tag 并推送**：推送 tag 会触发构建并上传到 Google Play，推送前向用户确认。
   ```bash
   git tag <版本号>
   git push origin <版本号>
   ```
5. **等待 CI**：用 `gh run list -R rikkahub/rikkahub --workflow release.yml` 找到这次运行，再 `gh run watch <run-id> -R rikkahub/rikkahub --exit-status` 等它结束。
   - 失败时用 `gh run view <run-id> -R rikkahub/rikkahub --log-failed` 查原因并告诉用户；修好后用 `gh run rerun <run-id> -R rikkahub/rikkahub --failed` 重跑，不要删 tag 重打。
   - 版本号检查失败说明 tag 和 `versionName` 不一致，和用户商量是删掉 tag 重打还是改版本号。
6. **填写更新日志**：确认草稿 Release 上已有 APK 和 mapping zip，然后把确认过的更新日志写进草稿：
   ```bash
   gh release edit <版本号> -R rikkahub/rikkahub --notes-file <日志文件>
   ```
7. **发布**：向用户确认后发布草稿：
   ```bash
   gh release edit <版本号> -R rikkahub/rikkahub --draft=false --latest
   ```
8. **提醒 Play 草稿**：AAB 停在 Play Console 的草稿里，需要用户自己填写版本说明并发布。把更新日志拆成中文、英文两段分别给用户粘贴
   （去掉「更新内容:」「Updates:」标题；Play 每种语言限 500 字符，超出就请用户删减）。
