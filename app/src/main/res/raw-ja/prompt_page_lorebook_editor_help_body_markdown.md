# Lorebook の編集

このシートでは、1 つの book とその entry 一覧を管理します。

## Book レベルの項目

- **Name**: 整理のための名前です。
- **Description**: 人が読むための任意のメモです。
- **Enabled**: book 全体のオン / オフを切り替えます。
- **Recursive scanning**: すでにトリガーされた entry が、後続のパスでさらに多くの entry を有効化できるようにします。
- **Token budget**: この book から挿入できるテキスト量を制限します。

## 推奨ワークフロー

1. book を作成します。
2. recursive にするかどうかを設定します。
3. token budget が必要かどうかを決めます。
4. entry を 1 つずつ追加します。
5. 実際のチャット例でテストします。

## デモ用ワークフロー

```text
Book:
School Life

Use case:
授業、部活、寮、または試験の話題が出たときにだけ、キャンパスの詳細を挿入します。

Suggested setup:
- Recursive scanning: 最初はオフ
- Token budget: 最初は空のまま
- dorm、student council、exam rules、club culture 用にそれぞれ個別の entry を追加
```

## Recursive scanning を変更するタイミング

あるトリガーされた entry が、後続のパスで関連する entry を呼び起こせるようにしたい場合にのみオンにしてください。

> ほとんどの book はシンプルに始めるべきです: Recursive scanning はオフ、token budget はなし。必要になったら後で調整してください。
