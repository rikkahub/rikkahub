# i18n

管理 Android `strings.xml` 翻译的 CLI，基于 Bun、citty 和 Vercel AI SDK，可调用任何兼容 OpenAI 的接口。

## 准备

```bash
cd tools/i18n
bun install
```

在 `tools/i18n/.env` 中配置 API（不会提交到仓库）：

```
OPENAI_API_KEY=your_api_key
OPENAI_BASE_URL=https://api.openai.com/v1
```

模块、语言、术语表、模型和 prompt 在 `config.yml` 中配置。

## 命令

```bash
bun run i18n --help                                      # 查看全部命令，<command> --help 查看命令选项
bun run i18n add hello_world "Hello, World!"             # 添加英文条目并翻译到所有语言
bun run i18n add greeting "Welcome" -m ui --skip-translate
bun run i18n set hello_world "你好，世界！" -l values-zh   # 手动修改某个语言
bun run i18n remove hello_world greeting                 # 从所有语言中删除
bun run i18n list --missing                              # 列出缺少译文的条目
bun run i18n list --match 'memory'                       # 按 key 或英文原文搜索
bun run i18n translate-missing --dry-run                 # 统计缺失条目
bun run i18n translate-missing -l values-ar,values-ja    # 补全指定语言（多个值用逗号分隔）
bun run i18n retranslate -l values-ja --match '\bmemory\b'
bun run i18n retranslate -l values-ja --key some_key,other_key
bun run i18n unused                                      # 检测未引用的条目和多余的译文
bun run i18n unused --delete
bun run i18n test-connection
```

在仓库根目录可以用 `bun tools/i18n/src/cli.ts <command>`。

## 实现说明

- 读写 `strings.xml` 时只改动涉及的 `<string>` 元素，注释、`<plurals>`、`<string-array>`、空白和顺序原样保留；新条目追加到文件末尾。
- 值是元素内部的原始 XML（`&amp;`、`\'` 保持原样）。`add`/`set` 的输入按纯文本处理，自动转义 `&`、`<`、`>` 和引号；加 `--raw` 可直接写 XML。
- `translatable="false"` 的条目不参与翻译和缺失统计。
- 译文会做规范化（转义裸引号和 `&`），并校验格式符、`\n` 数量和内联标签与原文一致；不通过的条目会重试，仍失败的不写入，最后统一列出，命令以非零状态退出。
- 术语表按目标语言追加到 prompt 末尾。
- `unused` 扫描 `source_patterns` 中所有文件的 `R.string.xxx` / `@string/xxx` 引用（不分模块）；动态拼接 key 的引用无法识别，删除前需确认。

## 测试

```bash
bun test                                        # 离线单元测试
I18N_LIVE=1 bun test test/translator.live.test.ts  # 调用真实 API
bun run typecheck
bun run fmt                                     # oxfmt 格式化（fmt:check 只检查）
```
