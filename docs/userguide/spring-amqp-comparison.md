# Spring AMQP との機能対応

比較対象は [spring-projects/spring-amqp](https://github.com/spring-projects/spring-amqp) と Spring AMQP 4.1 の [template](https://docs.spring.io/spring-amqp/reference/amqp/template.html)、[@RabbitListener](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-annotation-driven.html)、[listener container](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-consumer.html) の公開仕様です。IBM MQ と RabbitMQ のプロトコルは異なるため、API 名と用途を比較しています。

| Spring AMQP の用途 | この starter の対応 | 状態 |
| --- | --- | --- |
| `RabbitTemplate.send` | `IbmmqTemplate.send`、MQMD/PMO カスタマイズ | 対応 |
| `convertAndSend` と `MessageConverter` | `convertAndSend`、`IbmmqMessageConverter` | 追加 |
| `receive` / `receiveAndConvert` | 同名の同期受信 API。タイムアウト時 `null` | 追加 |
| `@RabbitListener` のメソッド呼び出し | `@IbmmqListener`、固定の `concurrency`、`waitInterval` | 対応 |
| listener ごとの変換器 | `messageConverter` で bean を指定 | 追加 |
| listener のエラー処理 | `errorHandler` で `REQUEUE` / `DISCARD` を選択 | 追加 |
| listener container の停止・再接続 | `SmartLifecycle`、接続断時の再接続 | 対応 |
| native client のオプション | 接続プロパティ、MQMD、MQPMO、MQGMO を直接調整 | 対応 |

## プロトコル上の違い

- Spring AMQP は exchange と routing key で配送先を決めます。この starter の送信先は IBM MQ のキュー名です。
- RabbitMQ の publisher confirms / returns、prefetch、RabbitAdmin による exchange と binding の宣言は、そのまま IBM MQ の同名機能へ置き換えられません。
- 同期受信は現実装では `MQGMO_NO_SYNCPOINT` です。処理後の commit/backout が必要なら `@IbmmqListener` または native API を使います。
- IBM MQ の topic 購読、request/reply、動的 concurrency、listener ごとの起動制御、手動 ack に相当する API、接続プール、backout queue 自動移送はこの版にはありません。RabbitMQ 側の全機能との互換を宣言するものではありません。

実装した API の使い方は [利用ガイド](README.md)、未対応機能の運用上の扱いは [提案書](../proposal/ibmmq-starter.md) を参照してください。
