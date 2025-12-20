# locale-tui

Android 语言文件翻译管理 TUI 工具。

## 功能

- 模块选择界面
- 翻译表格显示所有语言
- AI 自动翻译缺失条目
- Dead entry 检测和过滤
- 搜索过滤
- 编辑和删除条目

## 快捷键

| 快捷键 | 功能 |
|--------|------|
| `Enter` | 选择/编辑 |
| `t` | AI翻译缺失条目 |
| `d` | 切换Dead Entry过滤 |
| `m` | 切换Missing过滤 |
| `/` | 聚焦搜索框 |
| `Delete` | 删除条目 |
| `s` | 保存更改 |
| `r` | 刷新数据 |
| `Escape` | 返回/取消 |
| `q` | 退出 |

## 运行

```bash
cd locale-tui
uv run python src/main.py
```

## 配置

编辑 `config.yml` 配置模块列表、语言列表和翻译设置。

在 `.env` 文件中配置 OpenAI API：

```
OPENAI_API_KEY=your_api_key
OPENAI_BASE_URL=https://api.openai.com/v1
```
