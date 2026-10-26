# Apache OFBiz 統合型ケース管理 要件・仕様書

- 対象: Apache OFBiz 24.09.07 / `plugins/casemgmt`
- 文書状態: v1.0
- 最終更新: 2026-10-25
- テスト基準: 77 test methods / 216 tests（`ofbiz --test component=casemgmt`）
- 位置づけ: 本ドキュメントを現行の要件・仕様とする。
- 関連: `README.md`

---

## 1. 目的

Apache OFBiz の業務アプリケーションと標準フォームを活用しながら、メタデータ駆動のケース管理を OFBiz に統合する。

本ケース管理の目的は、BPMN エンジンや BPMS を再実装することではない。以下を提供する。

- ケースタイプ、ステージ、ステップ、遷移、SLA、割当のメタデータ管理
- `WorkEffort` を基盤としたケースとタスクの実行
- OFBiz の標準フォームをケース開始・タスク処理に組み込む仕組み
- OFBiz の業務エンティティとケースを `CaseContext` で接続する仕組み
- 監査、可視化、プロセスマップ、シミュレーション
- 複数サーバー運用に耐えるトランザクション制御と排他制御

### 1.1 対象ユースケース

- 新入社員オンボーディング
  - 社員登録、初回研修、PC 支給、社内システムアカウント発行
- 新規取引先の登録と与信
  - 営業依頼、与信審査、取引先登録または有効化
- ローン見積もり
  - 与信、見積作成、顧客提示
- 休暇申請
- 顧客苦情、サービス要求
- 自分の担当ケースの確認
- ケースタイプの管理とプロセスマップ参照

### 1.2 対象外

- BPMN 2.0 のパーサ／実行エンジン
- 汎用ビジュアルモデラー
- 任意のスクリプト実行環境
- ケース管理エンジン自体による人事・与信・ローン業務の全面実装
- 外部 BPMS 製品への依存
- 既存の外部ケース管理製品からのデータ移行
- すべての OFBiz 標準フォームの無改造埋め込み

---

## 2. 設計原則

1. **ケースは OFBiz 業務アプリの操作盤**とする。業務データの正は業務エンティティに置き、ケース側にコピーしない。
2. **`Case` は `WorkEffort` の 1:1 拡張**とする。`caseId = workEffortId`、`workEffortTypeId = CASE`。
3. **人間タスクは `CASE_ASSIGNMENT`** の子 `WorkEffort` とし、進行判定の正は `CaseTask` とする。
4. **ケース進行は `CaseFlowEngine` に集約**する。Entity ECA / SECA は副作用、通知、監査、逸脱検知に限定する。
5. **メタデータはバージョン固定**とする。稼働中ケースは作成時の `definitionVersion` で実行する。
6. **フォーム表示と業務処理を分離**する。標準フォームをそのままケースの submit 先にしない。
7. **標準フォームは再利用**するが、ケース固有の target、入力検証、トランザクション、権限はアダプターで制御する。
8. **ケース管理カーネルは業務コンポーネントに依存しない**。人事・与信・ローンなどの連携はオプションの統合プラグインに置く。
9. **監査可能**とする。ケースの状態変化、タスク完了、フォーム送信、外部シグナルを `CaseEvent` に記録する。
10. **クラスタ安全**とする。サービス呼び出しのトランザクションだけに頼らず、状態遷移は条件付き更新または楽観ロックで保護する。
11. **コア改修は最終手段**とし、まず Entity ECA、View Entity、プラグイン画面、標準フォームのラップで対応する。
12. **UI とメタデータの整合性**を保つ。ステップ種別ごとに必要な設定項目を画面で出し分ける。

---

## 3. アーキテクチャ

```text
┌────────────────────────────────────────────────────────────┐
│ プレゼンテーション層                                        │
│  ケース開始ウィザード / ケース詳細 / タスクフォーム / 管理UI │
├────────────────────────────────────────────────────────────┤
│ フォーム統合層                                              │
│  StartCase / TaskForm / OFBiz Form Adapter                  │
├────────────────────────────────────────────────────────────┤
│ ケースカーネル                                              │
│  CaseFlowEngine / Assignment / SLA / Access / Context       │
├────────────────────────────────────────────────────────────┤
│ メタデータ層                                                │
│  CaseType / Stage / Step / Transition / StartForm / StepForm│
├────────────────────────────────────────────────────────────┤
│ OFBiz 基盤                                                  │
│  WorkEffort / Party / Quote / Agreement / HumanRes / ...    │
└────────────────────────────────────────────────────────────┘
```

### 3.1 コンポーネント構成

- `plugins/casemgmt`
  - ケース管理カーネル
  - 汎用 UI
  - メタデータ管理
  - 標準フォーム統合の共通 API
- `plugins/casemgmt-humanres`（新規候補）
  - オンボーディング用ケースタイプ
  - 人事標準フォームのアダプター
  - 研修、PC 支給、アカウント発行の開始・完了サービス
- `plugins/casemgmt-finance`（新規候補）
  - 与信、ローン見積もり用ケースタイプ
  - 与信フォーム、ローン見積フォーム
  - efinance サービスとのアダプター

ケース管理カーネルは、人事・与信・ローンのエンティティやサービスを直接参照しない。

---

## 4. ユースケース別の適用方針

| ユースケース | OFBiz 標準フォームの利用 | カスタムが必要な部分 |
|---|---|---|
| 新入社員オンボーディング | 社員登録、研修、固定資産割当、ユーザーログイン | オンボーディング開始フォーム、タスク連携、外部システムアカウント |
| 新規取引先・与信 | 取引先・個人・グループ登録 | 与信申請、与信判断、与信限度額、審査記録 |
| ローン見積もり | 汎用見積は参考になるが、ローン固有項目は不足 | 与信、ローン条件、返済スケジュール、見積提示 |
| 休暇申請 | 休暇申請フォーム | ケース開始、休暇申請とケースのリンク |
| 苦情・サービス要求 | 顧客・連絡先・コンテンツ | ケース固有の受付、SLA、ルーティング |

### 4.1 オンボーディング

```text
ケース開始
  ├─ 入社情報の入力
  ├─ 社員登録
  ├─ 初回研修
  ├─ PC 支給
  └─ システムアカウント発行
```

- 開始フォームはオンボーディング専用。
- 各タスクは標準フォームまたは標準サービスをラップする。
- 完了時にケースへ戻り、次のタスクへ進む。

最小実装は `plugins/casemgmt-humanres` にあり、`ONBOARDING` ケースタイプで「開始 → 社員登録 → 研修 → 設備 → アカウント → 完了」の5段階を実行する。開始時に `Person` とメールアドレスを作成し、各タスクで `Employment`、`PartyRelationship`、`PersonTraining`、`FixedAsset`、`PartyFixedAssetAssignment`、`UserLogin` を更新する。テストは `ofbiz --test component=casemgmt-humanres` で実行する。

### 4.2 新規取引先・与信

```text
ケース開始
  ├─ 取引先情報の入力
  ├─ 与信審査
  ├─ 与信判断
  └─ 取引先登録または有効化
```

- 取引先登録は OFBiz 標準フォーム・サービスを利用可能。
- 与信審査はカスタムフォーム・カスタムエンティティ。
- 与信結果を `CaseContext` でケースに接続する。

### 4.3 ローン見積もり

```text
ケース開始
  ├─ 与信審査
  ├─ ローン見積作成
  └─ 顧客提示
```

- 標準 `Quote` フォームは汎用見積向けであり、ローン固有項目には不足する。
- efinance のローンサービスを呼ぶ専用フォームを用意する。
- 与信、見積、提示の各タスクをケースで管理する。

---

## 5. 機能要件

状態の凡例:

- **実装済み**: 現行実装で利用可能
- **一部**: エンジンまたは画面の一部のみ実装済み
- **新規**: 本次要件で追加する

### 5.1 ケースタイプ管理

| ID | 要件 | 状態 |
|---|---|---|
| FR-001 | ケースタイプをドラフト・公開・退役の状態で管理できる | 実装済み |
| FR-002 | ステージ、ステップ、遷移を定義バージョン単位で管理できる | 実装済み |
| FR-003 | ステージ／ステップ／遷移の追加・更新・削除・並び替えができる | 実装済み |
| FR-004 | 定義の検証、公開、ドラフト複製、バージョン差分ができる | 実装済み |
| FR-005 | ステップ種別をドロップダウンで選択できる | 実装済み |
| FR-006 | ステップ種別に応じて必要な入力項目だけを表示する | 実装済み |
| FR-007 | ステップ種別ごとの必須設定を公開前に検証する | 一部（Wait／Sub-case） |
| FR-008 | ケースタイプのプロセスマップを編集中に参照できる | 実装済み |
| FR-009 | 開始フォームを定義バージョン単位で設定できる | 実装済み |
| FR-010 | タスクフォームをステージ／ステップ単位で設定できる | 実装済み |

### 5.2 ケース開始

| ID | 要件 | 状態 |
|---|---|---|
| FR-011 | ケースタイプを選択してケースを開始できる | 実装済み |
| FR-012 | 開始フォームが定義されている場合、フォーム送信後にケースを開始する | 実装済み |
| FR-013 | 開始フォームが未定義の場合、従来の簡易作成で開始できる | 実装済み |
| FR-014 | 開始フォーム画面にケースタイプのプロセスマップを表示する | 実装済み |
| FR-015 | フォーム送信とケース作成を同一トランザクションで実行する | 実装済み |
| FR-016 | 開始フォームで作成した業務エンティティをケースにリンクする | 実装済み |
| FR-017 | 開始フォームの送信に失敗した場合、業務エンティティとケースをロールバックする | 実装済み |

### 5.3 フォーム統合

| ID | 要件 | 状態 |
|---|---|---|
| FR-018 | OFBiz 標準フォームをケース画面に埋め込める | 実装済み |
| FR-019 | 標準フォームの target を変更せず、ケース専用の submit 先に接続できる | 実装済み |
| FR-020 | 標準フォームを `extends` または `auto-fields-service` で再利用できる | 実装済み |
| FR-021 | フォーム定義の場所と名前をメタデータで指定できる | 実装済み |
| FR-022 | フォーム送信後の完了処理をサービスで指定できる | 実装済み |
| FR-023 | タスクフォーム送信とタスク完了を同一トランザクションで実行する | 実装済み |
| FR-024 | フォームの権限はケース権限と業務権限の両方で検証する | 実装済み |
| FR-025 | 標準フォームの直接 target 呼び出しを禁止する | 実装済み |

### 5.4 ステップ種別

| ID | 要件 | 状態 |
|---|---|---|
| FR-026 | Human Task は担当者・キュー・フォームを設定できる | 実装済み |
| FR-027 | Service ステップはサービス名と入力マッピングを設定できる | 一部 |
| FR-028 | Decision ステップは判断サービスと outcome を設定できる | 一部 |
| FR-029 | Decision の分岐条件を遷移側で設定できる | 実装済み |
| FR-030 | Wait ステップはシグナル名、タイムアウト、タイムアウト時 outcome を設定できる | 実装済み |
| FR-031 | Sub-case ステップはステップ単位で子ケースタイプを設定できる | 実装済み |
| FR-032 | ステップ種別ごとに不要な項目を無効化または非表示にする | 実装済み |
| FR-033 | Wait の外部シグナルを画面または API から送信できる | 一部 |
| FR-034 | Sub-case の子ケース完了で親タスクを完了できる | 実装済み |

### 5.5 サービス入力マッピング

| ID | 要件 | 状態 |
|---|---|---|
| FR-035 | サービス入力名をメタデータで定義できる | 実装済み |
| FR-036 | 固定値、ケース項目、実行コンテキスト、業務エンティティ項目から入力値を取得できる | 一部（`FORM_VALUE` 未対応） |
| FR-037 | 入力値の型変換と必須チェックを OFBiz サービスモデルに従って行う | 一部（サービス定義による検証、出力変数の型変換） |
| FR-038 | サービス結果の `outcomeEnumId` で遷移先を決定できる | 実装済み |
| FR-039 | サービスエラー時にケースを進めず、エラーを記録する | 実装済み |
| FR-040 | サービス入力マッピングをバージョン差分・複製・検証の対象にする | 一部（複製・差分） |

### 5.6 ケースと業務データのリンク

| ID | 要件 | 状態 |
|---|---|---|
| FR-041 | ケースと業務エンティティを `CaseContext` でリンクできる | 実装済み（単一・複合PK） |
| FR-042 | 複合主キーの業務エンティティをリンクできる | 実装済み |
| FR-043 | 主業務エンティティと関連エンティティを区別できる | 実装済み |
| FR-044 | リンクの追加・解除・取得をサービスで提供する | 実装済み |
| FR-045 | 同一リンクの重複を DB 制約でも防止する | 一部（サービス層で防止） |
| FR-046 | リンク先エンティティの存在確認と削除時の整合性を保つ | 一部（リンク時検証・解除時のキー削除） |

### 5.7 オーナー・割当・権限

| ID | 要件 | 状態 |
|---|---|---|
| FR-047 | ケース全体のオーナーを設定できる | 実装済み |
| FR-048 | ステージ単位のオーナーは持たない | 方針 |
| FR-049 | グループはキューとメンバーで運用できる | 実装済み |
| FR-050 | タスクの特定担当、ラウンドロビン、最小負荷、スキル割当ができる | 実装済み |
| FR-051 | claim、reassign、release ができる | 実装済み |
| FR-052 | 行レベルアクセス制御を主要サービスで強制する | 実装済み |
| FR-053 | メイン画面で自分に割り当てられたケースを表示できる | 実装済み |
| FR-054 | キュー未割当案件は「自分の担当」に含めない | 実装済み |
| FR-085 | メインとケースワークリストを分離し、ワークリストは権限内の全ケースを条件検索できる | 実装済み |
| FR-086 | 分析・レポートの集計行から条件付きケースワークリストへ遷移できる | 実装済み |
| FR-087 | クローズ済ケースの表示期間を非表示／1週間／1か月／1年から選択できる | 実装済み |
| FR-088 | ケースタイプ編集画面でステージ図、ステップ順序図、条件分岐付き遷移図を個別に表示できる | 実装済み |

### 5.8 可視化・説明可能性

| ID | 要件 | 状態 |
|---|---|---|
| FR-055 | ケース詳細にプロセスマップを表示する | 実装済み |
| FR-056 | 現在ステージ・現在ステップを強調表示する | 実装済み |
| FR-057 | 遷移、差し戻し、条件、デフォルト遷移を表示する | 実装済み |
| FR-058 | ケースタイプ編集画面で定義版プロセスマップを表示する | 実装済み（ステージ・ステップ・遷移） |
| FR-059 | グラフと表を切り替えられる | 実装済み |
| FR-060 | `explainCaseState` で現在状態とブロック理由を説明できる | 実装済み |
| FR-061 | フローシミュレータで定義の簡易ドライランができる | 一部 |
| FR-062 | シミュレータで条件・タスク・SLA を考慮した検証ができる | 新規 |

### 5.9 SLA・レポート・監査

| ID | 要件 | 状態 |
|---|---|---|
| FR-063 | SLA インスタンスを生成し、期限を監視できる | 実装済み |
| FR-064 | SLA の状態変化、違反、エスカレーションを記録できる | 実装済み |
| FR-065 | サイクルタイム、SLA、滞留、スループット、再作業を集計できる | 実装済み |
| FR-066 | CSV エクスポートができる | 実装済み |
| FR-067 | ケースの状態変化、タスク、フォーム送信、外部シグナルを監査できる | 一部 |
| FR-068 | 監査イベントに一意キーと発生元を記録できる | 新規 |

### 5.10 クラスタ・トランザクション

| ID | 要件 | 状態 |
|---|---|---|
| FR-069 | ケース操作サービスはトランザクション内で実行する | 実装済み |
| FR-070 | タスク完了・claim・シグナルは状態条件付き更新で二重処理を防ぐ | 実装済み |
| FR-071 | ケースのステージ遷移は楽観ロックまたは条件付き更新で直列化する | 一部（完了・シグナル・クローズ） |
| FR-072 | 外部シグナルは idempotency key で二重処理を防ぐ | 実装済み |
| FR-073 | SLA 監視ジョブは OFBiz JobSandbox の排他制御を利用する | 実装済み |
| FR-074 | SLA インスタンス更新は状態条件付き更新で保護する | 実装済み |
| FR-075 | 各サーバーの `unique.instanceId` を一意に設定する | 運用要件 |
| FR-076 | DB の一意制約で `CaseContext` などの重複を防ぐ | 新規 |
| FR-077 | ケースタイプ管理の同時更新をバージョン条件付き更新で保護する | 新規 |

### 5.11 統合・運用

| ID | 要件 | 状態 |
|---|---|---|
| FR-078 | 標準フォーム統合は業務別プラグインとして分離できる | 新規 |
| FR-079 | ケース管理カーネルは人事・与信・ローンに依存しない | 新規 |
| FR-080 | 統合プラグインは標準サービスの権限を尊重する | 新規 |
| FR-081 | フォーム定義、開始サービス、タスク完了サービスを監視・診断できる | 新規 |
| FR-082 | 開始フォーム・タスクフォームの設定をバージョン差分に含める | 実装済み |
| FR-083 | 既存の標準フォームを壊さずにアップグレードできる | 新規 |
| FR-084 | 日本語 UI と英語 UI のラベルを提供する | 実装済み |

### 5.12 デバッグ

| ID | 要件 | 状態 |
|---|---|---|
| FR-089 | 指定文字列をログ出力するデバッグサービスを提供する | 実装済み |
| FR-090 | サービス入出力パラメータと短期記録した `CaseVariable` をログ出力する | 実装済み |
| FR-091 | 対象サービスを実行し、入力・出力・ケース変数をログ出力する | 実装済み |

---

## 6. データモデル仕様

### 6.1 既存の中核エンティティ

- `CaseType`
- `CaseStageDef`
- `CaseStepDef`
- `CaseStepTransition`
- `CaseSla` / `CaseSlaMilestone` / `CaseSlaRule`
- `CaseAssignmentPolicy` / `CaseQueue` / `CaseQueueMember`
- `CaseAccessPolicy` / `CaseAccessRule`
- `Case`
- `CaseStageInstance`
- `CaseTask`
- `CaseSlaInstance`
- `CaseEvent`
- `CaseContext`
- `CaseContextKey`
- `CaseComment`
- `CaseDocument`

### 6.2 追加エンティティ

#### `CaseTypeStartForm`

ケース開始フォームを定義バージョン単位で保持する。

```text
caseTypeId
definitionVersion
formLocation
formName
formTarget
startServiceName
submitLabel
description
```

主キー:

```text
caseTypeId + definitionVersion
```

- `formLocation` / `formName`: `include-form` に渡すフォーム定義
- `formTarget`: `StartCaseSubmit` を指定する。保存時に実際のフォーム定義の target も検証する
- `startServiceName`: 業務エンティティを作成する開始サービス
- 未設定の場合は従来の簡易ケース作成を使用

#### `CaseStepForm`

人間タスクなどのステップフォームを保持する。

```text
caseTypeId
definitionVersion
stageSeq
stepSeq
formLocation
formName
formTarget
completeServiceName
description
```

主キー:

```text
caseTypeId + definitionVersion + stageSeq + stepSeq
```

- タスク画面でフォームを表示する
- `formTarget`: `TaskFormSubmit` を指定する。保存時に実際のフォーム定義の target も検証する
- `completeServiceName` はフォーム送信後の完了処理
- 未設定の場合は `CaseFieldMapping` による動的フォームを使用

#### `CaseFieldLabel`

動的フォーム項目のロケール別ラベルを保持する。

```text
caseTypeId
definitionVersion
stageSeq
stepSeq
fieldSeq
locale
label
description
```

主キー:

```text
caseTypeId + definitionVersion + stageSeq + stepSeq + fieldSeq + locale
```

- `CaseFieldMapping.label` は既定ラベルとして残す
- 表示時の優先順位は、完全一致ロケール、言語だけのロケール、既定ラベル、項目名
- 例: `ja_JP` の表示では `ja_JP`、`ja`、既定ラベルの順に探す
- ドラフト定義の項目編集画面から追加・更新・削除できる
- ステージ・ステップ・項目の移動、定義の複製・削除時にもラベルを追従させる

#### `CaseStepServiceParam`

Service / Decision ステップの入力マッピング。

```text
caseTypeId
definitionVersion
stageSeq
stepSeq
paramSeq
paramName
sourceTypeEnumId
sourceValue
requiredIndicator
description
```

`sourceTypeEnumId` の候補:

- `LITERAL` (`CASE_SVC_SRC_LITERAL`): 固定値
- `CONTEXT_FIELD` (`CASE_SVC_SRC_CONTEXT`): `caseId` などの実行コンテキスト
- `CASE_FIELD` (`CASE_SVC_SRC_CASE`): `Case` の項目
- `ENTITY_FIELD` (`CASE_SVC_SRC_ENTITY`): 主業務エンティティ／拡張エンティティの項目
- `CASE_CONTEXT` (`CASE_SVC_SRC_CCTX`): `CaseContext` でリンクしたエンティティの項目
- `CASE_VARIABLE` (`CASE_SVC_SRC_VAR`): `CaseVariable` の実行時変数
- `FORM_VALUE` (`CASE_SVC_SRC_FORM`): 動的フォームの保存値（未実装）

#### `CaseStepServiceOutput`

Service / Decision ステップの出力保存マッピング。宣言した項目だけを `CaseVariable` に保存する。

```text
caseTypeId
definitionVersion
stageSeq
stepSeq
outputSeq
outputName
variableName
valueTypeEnumId
requiredIndicator
sensitiveIndicator
scopeEnumId
ttlSeconds
description
```

- `outputName`: サービスの出力コンテキスト名
- `variableName`: 保存先のケース変数名
- `valueTypeEnumId`: `CASE_VAR_STRING` / `CASE_VAR_NUMBER` / `CASE_VAR_DATE` / `CASE_VAR_BOOLEAN` / `CASE_VAR_JSON`
- `scopeEnumId`: `CASE_VAR_SCOPE_CASE`（ケースクローズまで）/ `CASE_VAR_SCOPE_STAGE` / `CASE_VAR_SCOPE_STEP` / `CASE_VAR_SCOPE_TTL`
- `ttlSeconds`: `CASE_VAR_SCOPE_TTL` の期限
- `sensitiveIndicator`: 機密値のマスク・監査用フラグ
- 出力保存とステップ完了は同一トランザクションで実行する
- ケースクローズ時に実行時変数を削除する

#### `CaseVariable`

ケース実行中の短期的な変数。主キーは `caseId + variableName`。

```text
caseId
variableName
valueTypeEnumId
valueText
valueNumber
valueDate
valueBoolean
jsonValue
sourceCaseTaskId
scopeEnumId
sensitiveIndicator
expiresAt
createdDate
lastUpdatedDate
```

#### `CaseStepWait`

Wait ステップの設定。

```text
caseTypeId
definitionVersion
stageSeq
stepSeq
signalName
timeoutDuration
timeoutOutcomeEnumId
description
```

- `signalName` が一致するシグナルのみ受け付ける
- `timeoutDuration` は分単位。経過時に `timeoutOutcomeEnumId` で完了する
- `processCaseWaitTimeouts` を1分間隔で実行する
- 未設定の場合は従来の手動シグナル待ち

#### `CaseStepDef` の追加項目

- `subCaseTypeId`: Sub-case ステップの子ケースタイプ
- `formId`: 既存の動的フォーム ID
- `serviceName`: 既存のサービス名
- `waitConfig` は `CaseStepWait` に分離

`CaseTypeAttr.SUB_CASE_TYPE` は後方互換のため残し、ステップ単位設定を優先する。

### 6.3 `CaseContext` の複合キー対応

`CaseContext` は単一キーの `pkValue` を後方互換のため保持しつつ、複合キーを `CaseContextKey` 子テーブルに保持する（案A）。

```text
CaseContext
  caseId
  contextSeq
  entityName
  pkValue          # 単一キーの場合のみ設定
  contextTypeEnumId
  roleTypeId
  description

CaseContextKey
  caseId
  contextSeq
  keySeq
  keyName
  keyValue
```

- `businessKeys` は対象エンティティの主キー項目をすべて含む Map とする
- 単一キーは従来どおり `pkValue` でも指定できる
- 複合キーの場合、`pkValue` は設定せず `CaseContextKey` を正とする
- 既存の単一キーリンクは `CaseContextKey` がない場合、`pkValue` から読み替える
- `getCaseContext` は `businessKeys` Map を返す
- 解除時は `CaseContextKey` を先に削除する
- 重複判定は `entityName`、`roleTypeId`、全キー値で行う

### 6.4 同時実行制御用の項目

- `Case.version` を楽観ロックに使用する
- `CaseTask` は状態条件付き更新で保護する
- `CaseEvent` に `idempotencyKey` と一意制約を追加する → 実装済み
- `CaseContext` に一意制約を追加する
- `CaseSlaInstance` は状態条件付き更新で保護する

---

## 7. ケース開始仕様

### 7.1 画面フロー

```text
CaseWorklist
  → CreateCase（ケースタイプ選択）
  → StartCase（開始フォーム）
       ├─ プロセスマップ
       └─ OFBiz 標準フォームまたはケース専用フォーム
  → StartCaseSubmit
       ├─ startServiceName
       ├─ createCase
       ├─ 業務エンティティとケースのリンク
       └─ ViewCase
```

### 7.2 開始フォームの解決

1. `CaseTypeStartForm` を `caseTypeId + activeDefinitionVersion` で取得
2. 設定がある場合、`formLocation` / `formName` で `include-form`
3. 設定がない場合、従来の `CreateCase` フォームで作成
4. 画面上部に `getCaseTypeProcessMap` の定義グラフを表示

### 7.3 開始サービスの契約

`startServiceName` は以下を返す。

```text
businessEntityName
businessKeys
customerPartyId
ownerPartyId
description
contextLinks
```

`StartCaseSubmit` は以下を同一トランザクションで実行する。

1. `startServiceName`
2. `createCase`
3. `CaseContext` リンク
4. `ViewCase` へリダイレクト

エラー時は業務エンティティとケースをロールバックする。

---

## 8. タスクフォーム仕様

### 8.1 画面フロー

```text
CaseDetail
  → タスクを開く
  → TaskForm
       ├─ プロセスマップ
       ├─ CaseStepForm または CaseFieldMapping
       └─ 完了ボタン
  → TaskFormSubmit
       ├─ completeServiceName
       ├─ CaseTask の状態条件付き更新
       ├─ ケース遷移
       └─ CaseDetail
```

### 8.2 フォームの優先順位

1. `CaseStepForm`
2. `CaseFieldMapping` による動的フォーム
3. フォームなしの単純完了

### 8.3 標準フォームの接続

標準フォームを直接 `<include-form>` する場合は、以下のいずれかを行う。

- 標準フォームを `extends` し、target をケース用に変更
- `auto-fields-service` でケース専用フォームを作成
- 標準フォームの項目だけを参考に、統合プラグイン側でフォームを定義

標準フォームの target をそのまま使ってはならない。  
フォーム送信後は `TaskFormSubmit` または `StartCaseSubmit` に接続する。

実装例として、`CaseStandardPartyNoteForm` は標準 `AddPartyNote` を `extends` し、target を `TaskFormSubmit` に変更している。完了サービスには標準 `createPartyNote` を使用し、`TEST_STANDARD_FORM` で標準フォーム・標準サービスの再利用を検証する。

---

## 9. ステップ種別仕様

### 9.1 Human Task

- 担当ポリシー、キュー、フォーム、SLA を設定
- 担当者またはキューに割り当てる
- claim / reassign / release をサポート

### 9.2 Service

- `serviceName` を実行
- `CaseStepServiceParam` で入力を構築
- 結果の `outcomeEnumId` で遷移
- 失敗時はケースを進めない

### 9.3 Decision

- 判断サービスを実行
- 結果の `outcomeEnumId` で遷移
- 追加条件は `CaseStepTransition.conditionServiceName` で評価
- 任意のスクリプトや式言語は許可しない

### 9.4 Wait

- `CaseStepWait.signalName` で待機
- `signalCaseTask` がシグナル名と idempotency key を検証
- タイムアウト時は指定 outcome で完了
- UI からシグナル送信可能

### 9.5 Sub-case

- `CaseStepDef.subCaseTypeId` で子ケースタイプを指定
- 子ケースは親ケースの `customerPartyId` を引き継ぐ
- 子ケース完了で親タスクを完了
- 複数の子ケースは同一ステージ内で並行実行できる
- 子ケース完了時は親ケースをロックし、親タスクを `TASK_WAITING` からのCAS更新で完了する
- 子ケースのオーナー・キューはステップ設定で指定可能

---

## 10. サービス入力・出力仕様

### 10.1 入力の解決順

1. 固定コンテキスト
   - `caseId`
   - `caseTypeId`
   - `definitionVersion`
   - `stageSeq`
   - `stageVisitSeq`
   - `stepSeq`
   - `caseTaskId`
   - `userLogin`
2. `CaseStepServiceParam` による追加入力
3. フォーム値または業務エンティティ値
4. OFBiz の `ModelService` による型変換・必須チェック

### 10.2 出力

- `outcomeEnumId`: 遷移先の決定
- `conditionResult`: 条件評価
- `payload`: 監査・後続処理用
- `CaseStepServiceOutput` で宣言した項目だけを `CaseVariable` に保存する
  - `scopeEnumId`: `CASE_RUNTIME`（ケースクローズまで）、`STAGE_RUNTIME`、`STEP_RUNTIME`、`TTL`
  - `ttlSeconds`: `TTL` スコープの期限
  - `sensitiveIndicator`: 機密値のマスク・監査用フラグ
  - `CASE_VAR_JSON`: 複雑な構造を JSON として保存
- 出力保存とステップ完了は同一トランザクションで実行する
- ケースクローズ時に `CASE_RUNTIME` の変数を削除する
- サービスエラー時はケースを進めない

---

## 11. 同時実行・トランザクション仕様

### 11.1 基本方針

- 各更新サービスはトランザクション内で実行
- 読み取り専用サービスは `use-transaction=false` を許可
- 状態遷移は「読んでから更新」ではなく条件付き更新で行う
- 分散ロックは導入せず、DB の条件付き更新と一意制約を基本とする

### 11.2 タスク完了・claim・シグナル

`CaseTask` の更新は、現在の状態を条件に含める。

```text
UPDATE CaseTask
SET status = ...
WHERE caseTaskId = ?
  AND status = ?
```

更新件数が 1 の場合だけ、イベント作成とケース遷移を実行する。

現行実装では、`CaseConcurrencyHelper` により以下を条件付き更新している。

- `completeCaseTask`
- `signalCaseTask`
- `claimCaseTask`
- `releaseCaseTask`

また、タスク完了・シグナル・クローズでは `Case.version` を更新してケース行をロックし、同一ケースの同時進行を直列化する。

### 11.3 ケース遷移

`Case.version` を楽観ロックとして使用する。

```text
UPDATE Case
SET version = version + 1, currentStageSeq = ...
WHERE caseId = ? AND version = ?
```

競合時は再読込・再評価またはエラーとする。

### 11.4 外部シグナル

`signalCaseTask` は以下を必須とする。

- `caseTaskId`
- `signalName`（Wait 設定時）
- `idempotencyKey`
- `outcomeEnumId`

同一 `idempotencyKey` の再送は、既存結果を返し、イベントを重複作成しない。

現行実装では、`CaseEvent.idempotencyKey` の一意制約と `signalCaseTask` の事前照合により、以下を保証する。

- 同一タスク・同一キーの再送は `idempotent=true` で既存状態を返す
- 状態変更・タスク完了・遷移は再実行しない
- 同じキーを別タスクや別操作に再利用した場合はエラー
- 同時実行時はケースロックと一意制約で一方だけが成功する

### 11.5 SLA

- OFBiz JobSandbox の `runByInstanceId` による排他を利用
- `monitorCaseSla` は1つの定期ジョブのみ作成
- `CaseSlaInstance` の状態更新は `slaStateEnumId` / `escalationLevel` / `completedDate` を条件にした CAS で保護
- 状態変更イベントとエスカレーションは、CAS に成功した処理だけが実行
- エスカレーションはレベル更新を先に CAS で獲得してからサービスを呼び、二重実行を防止
- ケースの `slaStateEnumId` 更新も `Case.version` 条件付き更新

### 11.6 クラスタ設定

- 各サーバーの `unique.instanceId` を一意にする
- 全サーバーが同じ DB を参照する
- NTP で時刻を同期する
- JobSandbox をサーバーごとに重複作成しない

---

## 12. UI 要件

### 12.1 ケース開始

- ケースタイプ選択
- 開始フォーム
- プロセスマップ
- 送信エラー表示
- 送信成功後のケース詳細遷移

### 12.2 ケースワークリスト

- 自分の担当フィルタ
- キュー未割当の除外
- ケースタイプ、ステータス、担当者、期限表示

### 12.3 ケース詳細

- 現在ステージ・ステップ
- プロセスマップ
- タスク一覧
- タスクフォーム
- 関連リンク
- 履歴
- SLA

### 12.4 ケースタイプ管理

- ステップ種別ドロップダウン
- ステップ種別に応じた項目表示
- 開始フォーム設定
- タスクフォーム設定
- Service 入力マッピング
- Wait 設定
- Sub-case 設定
- 定義検証・差分・複製・公開

---

## 13. 非機能要件

| ID | 要件 |
|---|---|
| NFR-001 | 複数サーバーで同一ケースを同時操作しても二重遷移しない |
| NFR-002 | 同一タスクの二重完了・二重 claim・二重シグナルを防止する |
| NFR-003 | 全更新サービスはロールバック可能である |
| NFR-004 | ケース操作とフォーム送信を監査できる |
| NFR-005 | 10 万ケース規模でもワークリストと集計が実用的に動作する |
| NFR-006 | 標準フォームのアップグレード時にケース固有アダプターだけを修正できる |
| NFR-007 | 日本語・英語の UI ラベルを提供する |
| NFR-008 | ケース管理カーネルは業務コンポーネントに依存しない |
| NFR-009 | 既存の OFBiz 標準画面を壊さない |
| NFR-010 | 主要ユースケースを自動テストで検証できる |

---

## 14. 受け入れ条件

1. 休暇申請ケースを開始フォームから作成し、休暇申請とケースが同一トランザクションで作成される。
2. 開始フォーム画面にケースタイプのプロセスマップが表示される。
3. 標準フォームの項目を再利用したケース開始フォームが動作する。
4. タスクフォームから標準サービスを呼び、タスク完了とケース遷移が同一トランザクションで行われる。
5. Service / Decision / Wait / Sub-case の設定項目が画面で出し分けられる。
6. Wait のシグナル名・タイムアウトが設定でき、二重シグナルが防止される。
7. Sub-case の子ケースタイプをステップ単位で設定できる。
8. 2つのサーバーから同時に同じタスクを完了しても、成功するのは1つだけである。
9. SLA 監視ジョブが複数サーバーで二重実行されない。
10. 複合主キーの業務エンティティをケースにリンクできる。
11. 自分の担当ケースだけをワークリストに表示できる。
12. ケースタイプ編集画面でプロセスマップと定義差分を確認できる。

---

## 15. 実装ロードマップ

### Phase A: ケース管理カーネルの安定化

- タスク状態の条件付き更新
- ケース遷移の楽観ロック
- SLA 更新の条件付き更新
- CaseContext の一意制約
- ステップ種別 UI の動的化
- Service / Wait / Sub-case の設定項目追加

### Phase B: フォーム統合基盤

- `CaseTypeStartForm`
- `CaseStepForm`
- `CaseStepServiceParam`
- `CaseStepWait`
- 開始・タスクフォームの共通画面
- 標準フォームの `extends` / ラッパー方式の確立

### Phase C: 業務別統合

- `casemgmt-humanres`
  - オンボーディング（最小フロー実装済み）
  - 休暇申請
  - 研修・PC 支給・アカウント発行（最小フロー実装済み）
- `casemgmt-finance`
  - 与信
  - ローン見積もり

オンボーディングの実装は `plugins/casemgmt-humanres` に分離し、`Person`、`PartyRole`、`Employment`、`PersonTraining`、`FixedAsset`、`UserLogin` とケースを連携する。ケース管理カーネルは人事エンティティを直接参照しない。

### Phase D: 運用強化

- クラスタ構成の検証
- 監査イベントの idempotency
- 性能・障害試験
- 標準フォームのアップグレード手順

---

## 16. 未決事項

- 開始フォームを `CaseTypeStartForm` に持つか、ケースタイプ属性に持つか
- タスクフォームを埋め込みにするか、標準画面へのリンクにするか
- Service 入力マッピングの式言語をどこまで許可するか
- CaseContext の複合キーを子テーブル方式にするか、正規化キー方式にするか
- サブケースのオーナー・キュー継承ルール
- 与信・ローンのカスタムエンティティをどのプラグインに配置するか
- 監査イベントの保持期間と外部連携

---

## 17. まとめ

本要件では、ケース管理を OFBiz の業務アプリケーションに統合するための以下を中核とする。

- メタデータ駆動のケース進行
- OFBiz 標準フォームの再利用とケース専用アダプター
- 開始フォーム・タスクフォームのメタデータ管理
- 業務エンティティとのリンク
- キューとケースオーナーによる割当
- 監査・可視化・プロセスマップ
- クラスタ環境での条件付き更新・楽観ロック・冪等性

実装は、まずケース管理カーネルの安定化とフォーム統合基盤を整備し、その後、人事・与信・ローンの業務別プラグインへ展開する。
