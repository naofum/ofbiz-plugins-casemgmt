# AGENTS.md

## Project Context
- Apache OFBiz 上でケース管理を行うためのプラグイン
- Apache OFBiz Webアプリケーション
- The Data Model Resource BookのFinance Servicesモデルに準拠する

## Data Rules
- `data/*TypeData.xml` はseed、`data/*DemoData.xml` はdemo、`testdef/data/*` はテスト専用。用途を混在させない。
- Apache OFBizのテーブルのenumIdの文字長は最大20文字と短いことに注意する。
- 古いDB互換性のためFK名、インデックス名は18文字以下が推奨されている。
- エンティティの関係を作成する場合に名前の重複を避けるため、2目以降のrelationにtitleを指定して識別する。
- エンティティ名にModelEntityChecker.javaで定義されてる予約語の利用は避ける。

## Validation
- XML変更時は整形式を確認する。
- Java/Groovy変更時は次を実行する。

  ```powershell
  .\gradlew.bat compileJava compileGroovy
  .\gradlew.bat "ofbiz --test component=casemgmt"
  ```

- テスト前に通常のOFBizが停止していることを確認する。テスト用OFBizはコマンドが起動・停止する。
- テスト結果は `runtime/logs/test-results/casemgmttests.xml` で `errors="0"` と `failures="0"` を確認する。
- テスト実行を途中で中断すると、テスト用データが一部残り次回のテストが失敗することがある（例：`TEST_LOANAPP_AGR` の承認済み審査）。失敗時は残存データを確認する（詳細は `SPEC_COMMON.md` 運用メモ）。
- 新しいseedファイルを追加・登録した場合は、テスト前に `.\gradlew.bat "ofbiz --load-data readers=seed --load-data component=casemgmt"` でseedを投入する（テスト用delegatorは既定DBを共有する）。
- demoデータを変更した場合は、必要に応じて次を実行して実ロードを確認する。

  ```powershell
  .\gradlew.bat "ofbiz --load-data readers=demo --load-data component=casemgmt"
  ```

- PowerShellはWindows環境で文字化けしやすいので、文字コード UTF-8 を指定する。

## Change Boundaries
- core 機能、REQUIREMENTS、SPEC に変更が必要な場合は、理由、影響範囲、代替案、検証結果を、その変更を必要とした仕様書に記録する。

## Repository and Database Safety
- 旧テストDBテーブルや反復開発で残った列は、明示的な依頼なしに削除しない。
- 既知の旧テーブル・列に関する警告は、現行entity、データロード、テストへの影響を確認してから扱う。
