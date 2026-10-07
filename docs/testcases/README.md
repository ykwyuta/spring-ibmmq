# IBM MQ starter / demo テストケース

## 目的と実行条件

C1 は JaCoCo の **BRANCH 未網羅数 0** と定義する。root `pom.xml` の `jacoco:check` が starter と demo の各モジュールに適用され、未網羅分岐が1件でもあれば `mvn verify` は失敗する。正常な送受信は Docker Compose の実 IBM MQ に対して検証する。Mock は IBM MQ API から例外を発生させる試験に限って使用する。

実行前に Java 21、Maven、Docker Compose を準備し、リポジトリのルートで次を実行する。他のアプリが `DEV.QUEUE.1`、`DEV.QUEUE.2`、`DEV.QUEUE.3` を同時に使わない状態で実行する。

```powershell
$env:IBMMQ_PASSWORD = Read-Host 'IBM MQ demo password'
New-Item -ItemType Directory -Force demo/docker | Out-Null
Set-Content -NoNewline demo/docker/app-password.txt $env:IBMMQ_PASSWORD
docker compose up -d
mvn -B verify
```

レポートは `spring-boot-starter-ibmmq/target/site/jacoco/index.html` と `demo/target/site/jacoco/index.html` に作られる。XML と CSV も同じディレクトリに出力される。確認後は `docker compose down` でコンテナを停止できる。これはデータボリュームを削除しない。

## starter のケース

| ID | テスト | 条件・操作 | 主な期待結果 |
| --- | --- | --- | --- |
| S01 | `IbmmqAutoConfigurationTest.createsTemplateWithoutConnectingToMq` | 自動構成を起動し、接続先を変更 | template、factory、設定 bean が作られ、MQ 接続は不要 |
| S02 | `IbmmqAutoConfigurationTest.canBeDisabled` | `ibmmq.enabled=false` | template が登録されない |
| S03 | `IbmmqConfigurationValidationTest.optionalCredentialsAreOmittedWhenNullOrBlank` | user/password が null、空、設定済み | native 接続プロパティへの追加有無が正しい。カスタマイザで接続前に試験用例外を発生させる |
| S04 | `rejectsBlankQueueName` / `rejectsZeroConcurrency` | 空キュー名またはワーカー数0 | コンテキスト起動時に宣言エラー |
| S05 | `rejectsNoArgument` / `rejectsUnsupportedArgument` | 引数なし、既定変換器が非対応の引数 | コンテキスト起動時に宣言エラー |
| S06 | `rejectsNegativeWaitInterval` | MQGET 待ち時間が負値 | コンテキスト起動時に宣言エラー |
| S07 | `IbmmqNativeIntegrationTest.sendsTextBytesAndCustomizedMessageToRealQueue` | 文字列、バイト列、MQMD/PMO と syncpoint 付きで実 MQ へ送信 | 3件を受信でき、接続カスタマイザが送信ごとに適用される |
| S08 | `invalidQueueReportsNativeFailure` | 存在しないキューへ送信 | native の `MQException` を原因に持つ `IbmmqException` |
| S09 | `templateConvertsAndSynchronouslyReceives` | 空キュー、即時取得、待機取得、変換送信・受信、負の待ち時間 | null、本文、引数エラー、MQ エラーを確認 |
| S10 | `annotatedListenersReceiveAllSupportedArgumentTypesAndRetryFailure` | `String`、`byte[]`、`MQMessage` listener。最初の処理を1回失敗 | backout 後に再配送し、全引数型で受信する |
| S11 | `namedConverterAndErrorHandlerCanDiscardFailedMessage` | 独自型変換器と `DISCARD` を返すエラーハンドラ | 独自型で受信し、失敗メッセージは commit される |
| S12 | `SimpleIbmmqMessageConverterTest.convertsSupportedTypes` | String、byte[]、MQMessage | 既定変換器の書込・読取と対応型判定 |
| S13 | `SimpleIbmmqMessageConverterTest.rejectsUnsupportedTypes` | Integer を読み書き | 対応外として拒否 |
| S14 | `IbmmqTemplateFailureTest.connectionFailureDoesNotAttemptToCloseMissingManager` | Mock factory が接続例外を発生 | 例外を通知し、未作成ハンドルを閉じようとしない |
| S15 | `syncpointPutMqFailureBacksOut` / `syncpointPutRuntimeFailureBacksOut` | Mock MQPUT が例外を発生 | syncpoint 更新を backout |
| S16 | `runtimeFailureBeforePutDoesNotBackout` | 本文設定中に例外 | MQPUT 前の例外をそのまま通知 |
| S17 | `mqGetFailureOtherThanTimeoutIsReported` | Mock MQGET が 2033 以外の理由コードで失敗 | `IbmmqException` を通知 |
| S18 | `IbmmqListenerEdgeTest.rejectsGetOptionsWithoutSyncpoint` / `rejectsNegativeGetWait` | MQGMO の必須条件を外す | listener が設定を拒否し、再接続する |
| S19 | `nonTimeoutMqGetErrorsAreRetriedAndShutdownErrorsAreQuiet` | Mock MQGET から非タイムアウト例外を発生 | 稼働中は再試行し、停止時は再試行しない |
| S20 | `messageFetchedDuringShutdownIsBackedOut` | MQGET 中に停止状態へ移行 | 受信したメッセージを backout し、キューに残す |
| S21 | `cleanupAcceptsNoOpenHandles` | 接続前の後片付け | null ハンドルでも安全に終了 |
| S22 | `emptyQueueTimeoutContinuesPolling` | 空キュー、短い待ち時間 | MQRC_NO_MSG_AVAILABLE を正常なポーリングとして処理 |
| S23 | `IbmmqSpringAmqpReferenceIntegrationTest.stoppedListenerResumesWithoutLosingMessage` | listener 停止中に送信してから再開 | 停止中は受信せず、再開後に1件を受信してキューが空になる |
| S24 | `concurrentListenersReceiveDistinctMessages` | 3ワーカーを同時に待機させて3件送信 | 異なる3スレッドが重複なく受信し、キューが空になる |
| S25 | `conversionFailureReachesErrorHandlerAndDiscardsMessage` | 変換器が `IOException` を発生 | listener メソッドは呼ばれず、失敗内容がハンドラへ渡り、`DISCARD` で除去される |
| S26 | `listenerRecoversAfterTransientConnectionException` | 初回接続だけ `MQException` を注入 | 再接続後に実 MQ のメッセージを受信し、キューが空になる |

## demo のケース

| ID | テスト | 条件・操作 | 主な期待結果 |
| --- | --- | --- | --- |
| D01 | `DemoApplicationIntegrationTest.startupSendsAndBothListenersReceive` | 通常起動、実 MQ を使用 | 起動時の2件が `String` と `MQMessage` listener へ届く |
| D02 | `DemoApplicationIntegrationTest.receiveOnlyStartsWithoutSending` | `--receive-only` で起動 | 送信を省略し、listener bean は起動する |

## 合格基準

1. 全テストが成功する。
2. starter と demo の JaCoCo `BRANCH_MISSED` の合計がそれぞれ0である。
3. `mvn verify` の `jacoco:check` が両モジュールで成功する。

例外試験の Mock は `IbmmqTemplateFailureTest` と `IbmmqListenerEdgeTest.nonTimeoutMqGetErrorsAreRetriedAndShutdownErrorsAreQuiet` だけで使用し、正常系の送受信に代用しない。

Spring AMQP のテストとの対応と採用理由は [参照したテスト観点](spring-amqp-reference.md) にまとめた。
