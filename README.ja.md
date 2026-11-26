# Datadog Data Streams Monitoring デモ

[English](README.md)

Datadog Data Streams Monitoring（DSM）は、イベント駆動型 Application の
Producer、Topic、Consumer を Map 上に表示し、経路 Latency、Throughput、
Consumer lag を計測します。Message の遅延や滞留がどこで発生し、どの Service が
関係しているかを特定するための手掛かりを提供します。

このプロジェクトは、Kafka を利用した Java Application に DSM、APM、ログ、
Custom Metrics を計装する方法を示すデモです。ネット証券の国内現物株式における
注文と買付余力の流れを模擬します。顧客は 5 つの架空銘柄（`D101`〜`D105`）を
注文し、買付余力は同期的にチェックされ、Kafka が約定情報を買付余力、保有残高、
取引報告書、顧客通知、注文照会へ Fan-out します。Kafka は知っているものの、
Datadog による可観測性は初めて、という開発者を主な対象にしており、証券システムを
開発するエンジニアも想定しています。

> [!IMPORTANT]
> これは個人が作成した学習用プロジェクトであり、Datadog 公式サンプルでも
> 本番環境の参照 Architecture でもありません。実際の市場データ、顧客情報、
> 口座情報、注文、約定は使用していません。銘柄、口座、注文、価格、約定は
> すべてデモ用の架空値です。

## 学べること

- Datadog Java tracer による Kafka Producer／Consumer 経路の計装
- Fan-out Topic における Consumer Group ごとの経路 Latency、Throughput、
  Consumer lag の関連付け
- Unified Service Tagging による DSM、APM、ログ、Kubernetes の相関
- 高 Cardinality な Metrics を作らずに注文と口座を検索する方法
- Retry Topic、Dead Letter Topic、Redrive が DSM 上でどのように見えるか
- Streaming Backlog が顧客に与える影響を業務 Metrics で説明する方法

各計装方式と Datadog 上の表示の関係は、
[Datadog 学習ガイド](docs/datadog-learning-guide.md) で説明しています。

## デモに含まれるもの

Application は 10 個の Spring Boot Service、Strimzi が管理する 9 個の Kafka
Topic、PostgreSQL で構成されます。付属の Datadog Terraform は、Dashboard、
3 個の Monitor、Metric Metadata と Tag Configuration、APM Retention Filter、
任意の 7 個の Workflow Automation Control を作成します。

設計は、重要な点で実際のネット証券の慣行に沿っています。

- 買付余力または売却可能数量は、注文受付の前に同期的にチェックして拘束します。
  そのため、拒否は顧客へ即座に返ります。
- Kafka は約定の Fan-out を担います。ここでの lag は、売却代金が余力に未反映、
  注文照会が古い、通知が遅れる、といった形で顧客に影響します。
- Retry、Dead Letter、Redrive は冪等な顧客通知だけに適用します。注文を自動で
  再送することはありません。
- すべての Record は `account_id` を Key とします。買付余力のために口座単位の
  順序を保ちつつ、売り殺到時に Hot Partition を作りません。

デプロイ後は次の操作と観察ができます。

- DSM で注文と約定の Topology と Kafka の状態を確認する
- 顧客への影響を経路 Latency、lag、Trace、ログと関連付ける
- 寄付き集中、買付余力の反映遅延（「売ったのに買えない」）、Push Provider 障害と
  Redrive、Recovery、材料による売り殺到とストップ安のシナリオを実行する
- 1 個の `order_id` または `account_id` から Event、Span、Retry、最終結果を
  追跡する

以前のバージョンの Dashboard と DSM の Screenshot は削除しました。ネット証券版の
Screenshot は改めて取得する予定です。

Dashboard の **デモ操作** (Operator Controls) Tab は、Workflow Automation と Agent-based
Private Action Runner を通じてシナリオを実行します。Control Endpoint は
ClusterIP 限定です。Kubernetes Context を検証する `scripts/demo-control` CLI を
使用すれば、Workflow Automation なしでも同じ操作を実行できます。Dashboard の
Button を有効にする手順は [Private Action Runner ガイド](docs/private-action-runner.md)
を参照してください。

## アーキテクチャ

[詳細アーキテクチャ図](docs/architecture.md#system-and-observability-flow) では、
すべての Service、Kafka Topic、永続化、Datadog の計装、Telemetry 送信経路に
加え、市場ルールと意図的な簡略化を確認できます。

Java 21、Spring Boot、Spring for Apache Kafka、Apache Avro、KRaft Mode の
Strimzi Kafka、PostgreSQL、Helm、AWS CDK、Datadog Terraform Provider を
使用しています。ローカル Container Runtime は Podman で検証し、Release Image は
GitHub Actions で Build します。

## セットアップ

次のいずれかの Kubernetes 環境を選択します。どちらも同じ Application と Datadog
学習体験を提供します。

| デプロイ先 | 環境 | Kafka と Storage |
|---|---|---|
| kind | ローカル Kubernetes | Kafka 1 Node、開発用 Storage |
| Amazon EKS | AWS アカウントの Kubernetes | Kafka 1 Node、永続 EBS Storage |

どちらも Bash、kubectl、Helm、curl、jq、GitHub CLI、Datadog の API／Application
Key が必要です。Windows では WSL2 を使用してください。ローカル環境では kind、
OpenSSL、Podman 6 以降も必要で、Podman Machine の Memory は 12 GiB 以上
（16 GiB で検証済み）を割り当ててください。JDK 21 は Host 上で Java Test を
実行する場合だけ使用します。未検証の Docker 置換手順は
[Runbook](docs/demo-runbook.md#docker-compatibility-note-not-tested) に記載しています。

`DD_API_KEY`、`DD_APP_KEY`、`DD_SITE` は Password Manager または Secret Manager
から実行時に読み込んでください。解決済みの Key をリポジトリや Shell History に
保存しないでください。

Deploy Script は検証済みの Multi-architecture GHCR Release Image に対応して
いますが、ネット証券版の Image を含む Release はまだ公開されていません。既存の
`v0.1.0` Release はこのドメインより前のものです。新しい Release が公開される
までは、次のように Image を自分で Build してください。Provenance と Release 経路は
[Container Image の方針](docs/container-images.md) を参照してください。

### ローカル Kubernetes（kind）

```bash
scripts/kind-up
TARGET=kind scripts/build-images
BROKERAGE_DEMO_IMAGE_SOURCE=self-built scripts/kind-deploy
scripts/smoke-test kind
```

デプロイ後は DSM を `env:local` で絞り込みます。Smoke Test は `D101` 100 株の
成行買い注文を送信し、約定ごとに買付余力、取引報告書、通知が反映されて
約定済みになるまで確認します。

### Amazon EKS

> [!WARNING]
> EKS は課金対象の AWS リソースを作成し、Datadog へ Telemetry を送信します。
> 1 台の On-demand Worker、Control Plane、1 台の NAT Gateway、EBS Volume は
> Teardown まで課金対象です。Data Plane は Cost を抑えた Single-AZ 構成であり、
> 高可用性を備えた本番用 Topology ではありません。

EKS では、認証済み AWS CLI、Node.js、npm、明示的な管理者 CIDR と IAM Role、
PostgreSQL Credential、Worker Node から取得可能な Registry も必要です。
Infrastructure 作成、Image の Build、Deploy、Datadog Terraform、検証、Teardown の
完全な手順は [デモ Runbook](docs/demo-runbook.md) に記載しています。

## Datadog で確認できること

| 画面 | このデモで確認できること |
|---|---|
| Data Streams Monitoring | Service／Topic Topology、経路 Latency、Throughput、Consumer Group ごとの Consumer lag |
| APM とログ | 同期的な買付余力チェック、検索可能な口座と注文の Context、影響を受けた経路からの Drill-down |
| Dashboard と Metrics | 未約定注文、余力未反映の約定、反映遅延と通知遅延、注文拒否、株価、Dead Letter |
| Workflow Automation | 再現可能なシナリオ操作と実行履歴 |

Datadog Admission Controller がバージョンを固定した Java tracer を注入します。
Tracer は対応する Kafka Client を自動計装し、Trace と DSM Pathway Context を
伝播します。Application Code が DSM Checkpoint を手動で作成する必要はありません。

`order-state-service` は 9 個すべての Topic を消費し、主要な経路を見づらくするため、
デフォルトではこの Service だけ DSM を無効にしています。すべての Consumer を
表示する場合は、デプロイ前に `BROKERAGE_DEMO_DSM_FULL_TOPOLOGY=true` を設定して
ください。構造化ログには口座、注文、Event の Context を、Active Span には注文調査に
必要な Attribute を付加し、Unique ID は Metric Tag に使用しません。

計装の対応関係、DSM と APM の違い、Retry Header の伝播、Metric の規約は
[Datadog 学習ガイド](docs/datadog-learning-guide.md) を参照してください。

## デモシナリオ

Dashboard の **デモ操作** (Operator Controls) または CLI Fallback を使用します。

```bash
scripts/demo-control <kind|eks> <baseline|stop|opening-rush|sell-off|buying-power-delay|push-outage|redrive>
```

| シナリオ | Action | 内容 |
|---|---|---|
| 寄付き集中 | `opening-rush` | 60 秒間の寄付き前注文の後、板寄せで約定が一度に発生する |
| 買付余力の反映遅延 | `buying-power-delay` | 売却代金の余力反映が遅れ、乗り換えの買い注文が余力不足で拒否される |
| Push Provider 障害と Redrive | `push-outage`、その後 `baseline` と `redrive` | 通知が 2 個の Retry Topic を経て DLT へ移り、復旧後に Redrive される |
| Recovery | `baseline` | lag が解消し、買付余力が追い付く |
| 材料による売り殺到 | `sell-off` | `D105` がストップ安まで下落し、未約定の売り注文が積み上がって失効する |

実行内容、DSM と Dashboard での観察場所、期待されるおおよその値は
[demo-presentation-steps.md](docs/demo-presentation-steps.md) を参照してください。

## ドキュメント

| Document | 内容 |
|---|---|
| [Architecture](docs/architecture.md) | System、Kafka、永続化、Telemetry、市場ルール、簡略化 |
| [学習ガイド](docs/datadog-learning-guide.md) | DSM の読み方と APM、ログ、Metrics との関係 |
| [デモ Runbook](docs/demo-runbook.md) | Deploy、Operation、検証、Teardown |
| [シナリオと Presentation](docs/demo-presentation-steps.md) | 5 つのシナリオと再現可能な Presentation |
| [Container Image の方針](docs/container-images.md) | Image の取得元、Provenance、Digest 方針 |
| [Private Action Runner](docs/private-action-runner.md) | Dashboard Workflow Control の設定 |

Source Code は `apps/`、Kubernetes 設定は `deploy/`、AWS／Datadog Infrastructure は
`infra/`、操作 Command は `scripts/` に配置しています。

Datadog 公式ドキュメント:

- https://docs.datadoghq.com/data_streams/
- https://docs.datadoghq.com/data_streams/setup/technologies/kafka/
- https://docs.datadoghq.com/tracing/trace_collection/dd_libraries/java/
- https://docs.datadoghq.com/actions/private_actions/

Schema Tracking の参考情報は
https://docs.datadoghq.com/data_streams/schema_tracking/ を参照してください。このデモは
Schema Registry をデプロイせず、Schema Tracking が利用できない場合も動作します。

## セキュリティと移行

既存環境を更新する前に [セキュリティ・移行ガイド](docs/security-and-migration.md) を確認してください。
デプロイには Python 3 が必要です。API 認証・通信制限・DB 権限分離を適用し、既存データは自動初期化しません。

## サポートとライセンス

質問と不具合報告は GitHub Issues で受け付けます。Pull Request は受け付けません。
Issue には認証情報、顧客情報、本番 Payload、未加工のログを含めないでください。
[CONTRIBUTING.md](CONTRIBUTING.md) を確認し、脆弱性は
[SECURITY.md](SECURITY.md) に従って非公開で報告してください。

[MIT License](LICENSE) で公開します。
