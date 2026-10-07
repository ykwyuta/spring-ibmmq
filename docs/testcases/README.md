# IBM MQ starter / demo テストケース

## 目的と実行条件

C1 は JaCoCo の **BRANCH 未網羅数 0** と定義する。root `pom.xml` の `jacoco:check` が starter、demo、native-demo の各モジュールに適用され、未網羅分岐が1件でもあれば `mvn verify` は失敗する。正常な送受信は Docker Compose の実 IBM MQ に対して検証する。Mock は IBM MQ API から例外を発生させる試験に限って使用する。

実行前に Java 21、Maven、Docker Compose を準備し、リポジトリのルートで次を実行する。他のアプリが `DEV.QUEUE.1`、`DEV.QUEUE.2`、`DEV.QUEUE.3` を同時に使わない状態で実行する。

```powershell
$env:IBMMQ_PASSWORD = Read-Host 'IBM MQ demo password'
New-Item -ItemType Directory -Force demo/docker | Out-Null
Set-Content -NoNewline demo/docker/app-password.txt $env:IBMMQ_PASSWORD
docker compose up -d
mvn -B verify
```

レポートは各モジュールの `target/site/jacoco/index.html` に作られる。XML と CSV も同じディレクトリに出力される。確認後は `docker compose down` でコンテナを停止できる。これはデータボリュームを削除しない。

## starter のケース

| ID | テスト | 条件・操作 | 主な期待結果 |
| --- | --- | --- | --- |
| S01 | `IbmmqAutoConfigurationTest.createsTemplateWithoutConnectingToMq` | 自動構成を起動し、接続先を変更 | template、factory、設定 bean が作られ、MQ 接続は不要 |
| S02 | `IbmmqAutoConfigurationTest.canBeDisabled` | `ibmmq.enabled=false` | template が登録されない |
| S03 | `IbmmqConfigurationValidationTest.optionalCredentialsAreOmittedWhenNullOrBlank` | user/password が null、空、設定済み | native 接続プロパティへの追加有無が正しい。カスタマイザで接続前に試験用例外を発生させる |
| S04 | `rejectsBlankQueueName` / `rejectsZeroConcurrency` | 空キュー名またはワーカー数0 | コンテキスト起動時に宣言エラー |
| S05 | `rejectsNoArgument` / `rejectsUnsupportedArgument` | 引数なし、既定変換器が非対応の引数 | コンテキスト起動時に宣言エラー |
| S06 | `rejectsNegativeWaitInterval` | MQGET 待ち時間が負値 | コンテキスト起動時に宣言エラー |
| S07 | `IbmmqNativeIntegrationTest.sendsTextBytesAndCustomizedMessageToRealQueue` | 文字列、バイト列、MQMD/PMO と syncpoint 付きで実 MQ へ送信 | 3件を受信でき、送信接続は1本だけ作られる。close 後は送信できない |
| S08 | `invalidQueueReportsNativeFailure` | 存在しないキューへ送信 | native の `MQException` を原因に持つ `IbmmqException` |
| S09 | `templateConvertsAndSynchronouslyReceives` | 空キュー、即時取得、待機取得、変換送信・受信、負の待ち時間 | null、本文、引数エラー、MQ エラーを確認 |
| S10 | `annotatedListenersReceiveAllSupportedArgumentTypesAndRetryFailure` | `String`、`byte[]`、`MQMessage` listener。最初の処理を1回失敗 | backout 後に再配送し、全引数型で受信する |
| S11 | `namedConverterAndErrorHandlerCanDiscardFailedMessage` | 独自型変換器と `DISCARD` を返すエラーハンドラ | 独自型で受信し、失敗メッセージは commit される |
| S12 | `SimpleIbmmqMessageConverterTest.convertsSupportedTypes` | String、byte[]、MQMessage | 既定変換器の書込・読取と対応型判定 |
| S13 | `SimpleIbmmqMessageConverterTest.rejectsUnsupportedTypes` | Integer を読み書き | 対応外として拒否 |
| S14 | `IbmmqTemplateFailureTest.connectionFailureDoesNotAttemptToCloseMissingManager` | Mock factory が接続例外を発生 | 例外を通知し、未作成ハンドルを閉じようとしない |
| S14a | `syncpointConnectionFailureDoesNotBackoutMissingManager` | syncpoint 送信の接続時だけ例外を注入 | 未作成接続では backout せず例外を通知 |
| S14b | `runtimeConnectionFailureDoesNotBackoutMissingManager` / `runtimePutFailureWithoutSyncpointDoesNotBackout` | 接続生成またはsyncpointなしMQPUTで実行時例外を注入 | 未作成接続を操作せず、syncpointなしではbackoutしない |
| S14c | `receiveConnectionFailureDoesNotCloseMissingManager` | 同期受信の接続時だけ例外を注入 | 未作成接続を閉じずに例外を通知 |
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
| S27 | `senderReopensConnectionAfterFailure` | 実MQ送信接続を切断して送信 | 失敗を通知し、次の送信は新しい接続で成功する。失敗した送信は自動再送しない |
| S28 | `concurrentSyncpointSendsReuseOneConnection` | 4スレッドから8件をsyncpoint送信 | 接続は1本で、8件すべてを重複なく受信する |

## demo のケース

| ID | テスト | 条件・操作 | 主な期待結果 |
| --- | --- | --- | --- |
| D01 | `DemoApplicationIntegrationTest.startupSendsAndBothListenersReceive` | 通常起動、実 MQ を使用 | 起動時の2件が `String` と `MQMessage` listener へ届く |
| D02 | `DemoApplicationIntegrationTest.receiveOnlyStartsWithoutSending` | `--receive-only` で起動 | 送信を省略し、listener bean は起動する |

## native-demo のケース

| ID | テスト | 条件・操作 | 主な期待結果 |
| --- | --- | --- | --- |
| N01 | `NativeMqDemoIntegrationTest.defaultCommandRoundTrips` | 引数なし、実MQの `DEV.QUEUE.3` | 既定の本文を送受信し、キューが空になる |
| N02 | `sendAndReceiveCommandsUseNativeMqmdAndGmo` | `send` と `receive` を別々に実行 | 永続メッセージ、MQFMT_STRING、CCSID 1208、日本語UTF-8本文を確認 |
| N03 | `explicitRoundtripAndWaitTimeout` | 指定本文の往復、空キューで待機 | 指定本文を受信し、タイムアウト時にメッセージなしを通知 |
| N04 | `invalidCommandAndMissingQueueFailClearly` | 不明コマンド、存在しないキュー | 入力エラーと native MQ 例外を区別 |
| N05 | `unexpectedGetFailurePropagates` | MQGET に想定外の例外を注入 | タイムアウトとして扱わず例外を通知。Mock はこの例外試験だけで使用 |
| N06 | `configurationValidatesPasswordAndWait` | パスワード欠落・空欄、負の待ち時間または再接続間隔 | 設定エラーを通知し、正常設定は native 接続プロパティへ反映 |
| N07 | `listenPollsUntilStopped` | 実MQを常駐ポーリングし2件送信 | 2件を受信し、停止要求後に終了する |
| N08 | `listenReconnectsAfterConnectionBreak` | 実接続を切断して再送信 | 新しい接続で受信を再開する |
| N09 | `listenRetriesInitialConnectionFailure` | 初回接続だけ `MQException` を注入 | 再接続後に実MQのメッセージを受信する |
| N10 | `listenValidatesWaitAndStopsDuringReconnectDelay` | 待機時間0、存在しないキュー、停止要求 | 空回りを拒否し、再接続待ちから終了する |
| N11 | `sessionReusesOneConnectionForMultipleCommands` | 一つの対話セッションで2件送信・2件受信 | 実MQへの接続生成は一回だけで、本文を順に受信する |
| N12 | `sessionReopensBrokenConnectionWithoutRepeatingFailedSend` | 最初の送信後に実接続を切断 | 失敗を通知し、次の入力で再接続。失敗した送信は再実行しない |
| N13 | `sessionHandlesInvalidCommandAndEndOfInput` | 無効入力、空キュー受信、入力終端 | 使用方法を表示し、接続を閉じて終了する |

## 合格基準

1. 全テストが成功する。
2. starter、demo、native-demo の JaCoCo `BRANCH_MISSED` の合計がそれぞれ0である。
3. `mvn verify` の `jacoco:check` が三つのモジュールで成功する。

例外試験の Mock は `IbmmqTemplateFailureTest`、`IbmmqListenerEdgeTest.nonTimeoutMqGetErrorsAreRetriedAndShutdownErrorsAreQuiet`、`NativeMqDemoIntegrationTest.unexpectedGetFailurePropagates` だけで使用し、正常系の送受信に代用しない。`listenRetriesInitialConnectionFailure` は最初の接続で例外を注入し、再試行後は実MQに接続する。

Spring AMQP のテストとの対応と採用理由は [参照したテスト観点](spring-amqp-reference.md) にまとめた。
