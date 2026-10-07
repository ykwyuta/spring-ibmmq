# Spring AMQP のテストから採用した観点

## 確認したテスト

| Spring AMQP のテスト | 確認した検証方法 | このプロジェクトへの適用 |
| --- | --- | --- |
| [EnableRabbitIntegrationTests](https://github.com/spring-projects/spring-amqp/blob/main/spring-rabbit/src/test/java/org/springframework/amqp/rabbit/annotation/EnableRabbitIntegrationTests.java) の `simpleDirectEndpoint` | 停止状態の listener を起動し、実ブローカー経由で受信と並列数を確認 | S23で停止・再開後の受信、S24で3ワーカーの同時受信を確認 |
| 同ファイルの `testInvalidPojoConversion` | 変換失敗時にエラーハンドラへ渡された例外を検査 | S25で変換例外の型・内容、メソッド非呼出、`DISCARD` 後のキューを確認 |
| [MessageListenerContainerRetryIntegrationTests](https://github.com/spring-projects/spring-amqp/blob/main/spring-rabbit/src/test/java/org/springframework/amqp/rabbit/listener/MessageListenerContainerRetryIntegrationTests.java) | 複数consumerで失敗・再試行を起こし、回数と処理後のキュー状態を確認 | 既存S10の再配送回数と、S26の接続障害後の復旧・キュー状態を確認 |
| [listenerテスト群](https://github.com/spring-projects/spring-amqp/tree/main/spring-rabbit/src/test/java/org/springframework/amqp/rabbit/listener) | lifecycle、recovery、error handler、transactionを分けた統合テストを配置 | 対象を独立したテストメソッドに分け、実 MQ を使用 |

Spring AMQP が使うRabbitMQ固有の exchange、routing key、acknowledgement、retry interceptorは、そのままIBM MQのAPIには移植しない。S23～S26は送受信と復旧に共通する振る舞いを検証する。通常経路はDocker Compose上の実IBM MQを使う。S26の初回接続のみ、例外注入用のファクトリーで `MQException` を発生させ、次回から実MQへ接続する。Mockは使用しない。

非同期処理は `CountDownLatch` と上限付きの待ち時間で完了を待つ。受信の有無だけでなく、処理スレッド、失敗内容、呼出回数、最終的なキューの空き状態を検査する。テスト開始時には使用するキューを空にし、テスト間のメッセージの混入を防ぐ。
