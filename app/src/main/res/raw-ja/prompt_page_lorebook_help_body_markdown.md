# Lorebookの機能

Lorebookは、キーワードで発動する世界設定情報です。

各Lorebookには多くのエントリが含まれます。各エントリは直近のコンテキストを監視し、一致するとその内容をプロンプトに挿入します。

## 適した用途

- 設定上の事実
- 関係性のメモ
- 勢力や場所の要約
- 関連する場合にのみ表示したい繰り返しのルール

## クイックスタート

1. Lorebookを作成します。
2. 使用するassistantに紐づけます。
3. 短く具体的なキーワードでエントリを追加します。
4. 簡潔なエントリ内容を書きます。
5. 実際のチャットメッセージでテストし、発動しすぎる場合はキーワードを絞り込みます。

## デモ

```text
Lorebook: Kingdom of Vale

Entry keywords:
vale, royal family, queen elira

Entry content:
Vale is a coastal kingdom ruled by Queen Elira. The royal family values diplomacy first and open war only as a last resort.
```

## ブックレベル設定

- **Recursive scanning**: 発動したエントリが、後続のパスでさらに別のエントリの発動を助けることがあります。
- **Token budget**: このLorebookが追加できる内容量の上限です。
- **Assistant bindings**: 共有するLorebookは、必要なassistantにのみ紐づけます。

## 実践的なアドバイス

- 巨大な1つのエントリよりも、小さなエントリを複数使う方が効果的です。
- 1つのキーワードが広すぎる場合は、補助キーワードや選択的なロジックを追加してください。
- **Constant Active** は必要な場合にのみ使ってください。

> 何かをコンテキストで言及されたときだけ表示したいなら、通常はLorebookが適したツールです。
