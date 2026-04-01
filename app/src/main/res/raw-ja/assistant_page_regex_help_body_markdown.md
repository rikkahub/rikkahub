# このエディタでできること

Regex ルールは、チャットパイプライン内の特定のタイミングでテキストを書き換えます。

次のような用途に使えます:
- インポートした SillyTavern の書式を整理する
- モデルに送信する前に名前や句読点を正規化する
- UI 上でのみノイズの多いテキストを隠す
- ユーザー入力、AI 出力、world info、スラッシュコマンド、reasoning などの ST スタイルの配置を対象にする

## クイックスタート

1. ルールにわかりやすい名前を付けます。
2. **Find Regex** にパターンを入力します。
3. **Replace String** に出力を書きます。
4. 実際のメッセージに影響させるのか、UI のみにするのか、プロンプトのみにするのかを決めます。
5. このルールが ST 由来なら、タイミングが元のスクリプトと一致するように **ST placements** を設定します。

## デモ 1: ニックネームの表記ゆれを統一する

```text
目標:
"Alicia" と "Ally" を "Alice" にする

Find Regex:
Alicia|Ally

Replace String:
Alice
```

## デモ 2: キャプチャしたグループを戻す前に整える

```text
目標:
一致した部分のうち有用な部分だけを残す

Find Regex:
Name:\s*(.*)

Replace String:
$1

Trim captured strings:
[
]
```

## 重要な項目

- **Trim captured strings**: 置換結果を書き戻す前に、キャプチャグループから余分なラッパーテキストを削除します。
- **RAW / ESCAPED macro substitution**: マッチング前に、regex 自体の中にある `{{char}}` や `{{user}}` などのマクロを解決します。
- **ST placements**: これが空でない場合、上記の汎用スコープより優先されます。
- **Run on edit**: 既存のメッセージが編集されたときにもこのルールを適用します。

> 目安: SillyTavern から regex をコピーする場合は、まず ST placements を設定してください。アプリ全体で使う一般的なクリーンアップルールを作る場合は、汎用スコープから始めてください。
