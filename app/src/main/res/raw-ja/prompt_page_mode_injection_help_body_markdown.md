# モードインジェクションとは

モードインジェクションは、手動で追加するプロンプトブロックです。

これは、lorebook エントリのようにキーワードでトリガーされるものではなく、ST プリセットの完全な構造でもありません。

次のような再利用可能なモードを使いたいときに使用します:
- study mode
- translation mode
- code review mode
- writing polish mode

## クイックスタート

1. インジェクションを1つ作成します。
2. モードを説明する名前を付けます。
3. システムプロンプトに近い位置を選択します。
4. 実際の指示をコンテンツに入力します。
5. 複数のインジェクションを同時に有効にできる場合は、priority を使って順序を制御します。

## デモ

```text
Name:
Explain Simply

Position:
After system prompt

Priority:
100

Content:
Explain concepts in plain language, avoid jargon, and use one short example when helpful.
```

## 位置の考え方

- **システムプロンプトの前**: 最も強い方向付け。通常は高レベルの振る舞い向けです。
- **システムプロンプトの後**: ほとんどの追加指示に対する、より安全なデフォルトです。

> より強い上書きが必要だと分かっている場合を除き、まずは **After system prompt** から始めてください。
