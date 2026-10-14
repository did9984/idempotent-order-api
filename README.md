# 冪等性を備えた注文 API

![ビルド・テスト](https://github.com/did9984/idempotent-order-api/actions/workflows/test.yml/badge.svg)

注文APIに冪等性キーと在庫管理のトランザクションを導入し、再試行や同時注文時のデータ整合性を検証する。

Java 17 · Spring Boot 3.5.16 · Spring JDBC · H2 · Maven Wrapper

## 実行方法

Java 17以上が必要です。MavenはWrapperが自動でダウンロードします。別途DBをインストールする必要はありません。

```powershell
# Windows PowerShell：リポジトリのルートで実行
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

```bash
# macOS / Linux
chmod +x mvnw
./mvnw verify
./mvnw spring-boot:run
```

サーバーURL：http://localhost:8082。H2のインメモリDBを使用するため、アプリを終了するとデータは初期化されます。3つのプロジェクトはそれぞれ異なるポートを使用します。

## 主な機能

- 商品一覧の取得、注文の作成・一覧取得
- Idempotency-Keyとリクエスト内容が同じ場合、既存の注文を返す：200 / replayed=true
- 新規注文には201 / replayed=falseを返す
- 同じキーで異なる商品や数量を指定した場合は409を返す
- 商品の行ロックにより、在庫数を超える販売を防止する
- 在庫の減算と注文の保存を1つのトランザクションで処理する

## API

| メソッド | パス | 説明 |
|---|---|---|
| GET | /api/products | 初期データの商品・価格・在庫を取得 |
| GET | /api/orders | 全注文を取得 |
| POST | /api/orders | 注文の作成・再試行。Idempotency-Keyヘッダー必須 |

入力エラーは400、存在しないリソースは404、データ競合は409を返します。

## リクエスト例

以下のcurlコマンドはBash / Git Bash用です。Windows PowerShellでは[examples/demo.ps1](examples/demo.ps1)を実行するか、[examples/requests.http](examples/requests.http)を利用してください。

```bash
curl -X POST http://localhost:8082/api/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-order-001' \
  -d '{"productId":1,"quantity":2}'
```

## 設計とテスト

Controller → Service → JdbcTemplate → H2の構成です。サービス層のトランザクション境界でDBの読み書きを処理します。

同じキーで8件のリクエストを同時実行し、注文が1件だけ作成され、在庫が1回だけ減算されることを確認します。また、異なるキーで数量2の注文を8件同時送信し、初期在庫10に対して成功5件・競合3件・最終在庫0となることを確認します。

自動統合テストを**6件**用意しています。GitHub ActionsでpushおよびPRごとにJava 17でビルドとテストを実行します。

キーは英数字と . _ : - の組み合わせで1〜100文字です。成功した注文のキーはアプリの実行中に保持し、失敗したリクエストは保存しません。価格は韓国ウォンのサンプル値で、決済・配送・注文キャンセル機能は含みません。異なる商品に同じキーを同時使用した場合は、DBのUNIQUE制約により409を返し、失敗したトランザクションの在庫減算もロールバックします。

このリポジトリは架空のデータを使用する小規模な学習用プロジェクトです。ログイン・権限管理や本番環境へのデプロイ設定は含みません。

## 半期評価の目標として活用

[目標・検証記録のテンプレート](docs/evaluation.md)に、目標文と完了基準をまとめています。実際に実行し、説明できる内容に基づいて、ご自身の取り組みを記録してください。

Spring BootのJava・ビルドツール要件：[公式ドキュメント](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。
