# Spring Boot 4.1 向け IBM MQ native starter 提案書

## 目的

JMS を使わず、IBM MQ classes for Java (`MQQueueManager`、`MQQueue`、`MQMessage`) を利用する。利用側には `@IbmmqListener` と `IbmmqTemplate` を提供し、MQMD、MQPMO、MQGMO、接続プロパティの調整を可能にする。Maven groupId は `io.github.spring-ibmmq` とする。Java の package 識別子には `-` が使えないため `io.github.spring_ibmmq` を採用する。demo の package は `com.example` とする。

## 対象バージョン

| 対象 | 採用値 | 根拠 |
| --- | --- | --- |
| Spring Boot | 4.1.1 | [Spring Boot Maven plugin documentation](https://docs.spring.io/spring-boot/maven-plugin/using.html) の 4.1 系現行版 |
| Java | 21 | Spring Boot 4.1 に利用する本プロジェクトの実行基盤 |
| IBM MQ Java client | `com.ibm.mq:com.ibm.mq.allclient:10.0.0.0` | [Maven Central](https://central.sonatype.com/artifact/com.ibm.mq/com.ibm.mq.allclient) |
| IBM MQ 開発用コンテナ | `icr.io/ibm-messaging/mq:10.0.0.0-r4` | [IBM mq-container releases](https://github.com/ibm-messaging/mq-container/releases) で確認した公開タグ |

IBM のコンテナ資料に記載されるタグは更新タイミングに差があるため、Compose は確認できたタグを固定する。新しい 10.0.x コンテナが公開されたら更新する。IBM の `ibmcom/mq:latest` は古いイメージなので採用しない。[IBM MQ container documentation](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=reference-mq-advanced-developers-container-image)。

## 構成

```text
spring-boot-starter-ibmmq
  IbmmqAutoConfiguration
  IbmmqConnectionFactory
  IbmmqTemplate
  @IbmmqListener / IbmmqListenerRegistry
demo
  com.example.DemoApplication / DemoReceiver
compose.yaml
  IBM MQ Advanced for Developers
```

自動構成は `AutoConfiguration.imports` で登録する。`ibmmq.enabled=false` を指定すると starter の bean を作らない。`IbmmqConnectionFactory` は IBM MQ の `Hashtable` 接続プロパティを作り、`IbmmqConnectionPropertiesCustomizer` の各 bean を適用する。`MQEnvironment` の静的グローバル設定を変更しない。

`IbmmqTemplate.send(queue, String|byte[])` は UTF-8 メッセージを送る。`convertAndSend` は設定済みの `IbmmqMessageConverter` を使う。詳細が必要な送信では `send(queue, messageCustomizer, optionsCustomizer)` を使い、`MQMessage` と `MQPutMessageOptions` を直接設定する。`receive` と `receiveAndConvert` は同期受信を提供する。各操作は接続とキューハンドルを作って閉じる。大量処理向けの接続再利用は今後の課題とする。

`@IbmmqListener` はキュー名、ワーカー数、待ち時間、名前付き `IbmmqGetOptionsCustomizer` bean、変換器、エラーハンドラを指定できる。既定の変換器ではメソッドの引数は `String`、`byte[]`、`MQMessage` のいずれか一つ。独自変換器を指定すれば別の型も扱える。文字列は UTF-8 とする。各ワーカーは独立した `MQQueueManager` を持つ。IBM の資料では、一つの manager をスレッド間で共有すると待機中の MQGET が他の操作を妨げ得る。[MQQueueManager API](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqqueuemanager)。

## 受信処理と失敗時の動作

listener の MQGET には `MQGMO_SYNCPOINT` と有限の `waitInterval` を適用する。メソッド成功時は `MQQueueManager.commit()`、失敗時は既定で `backout()` とする。エラーハンドラが `DISCARD` を返した場合は commit して除去する。失敗後の `ibmmq.failure-delay`、接続断後の `ibmmq.reconnect-delay` を設定できる。カスタマイザが syncpoint を消す設定は拒否する。接続障害時にはハンドルを閉じて再接続する。IBM の [MQGMO API](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqgetmessageoptions) と [syncpoint 説明](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=work-syncpoint-considerations-in-mq-applications) に基づく。

この方式は MQ 受信と別の DB トランザクションを原子的には確定しない。処理側は重複配送に耐える設計が必要。メッセージ変換器と同期受信は Spring AMQP との比較で追加した。現時点では毒メッセージの backout queue 自動移送、トピック購読、接続プール、XA、監視メトリクスは対象外。特に例外が続くメッセージは再配送され続けるため、運用導入前に隔離方針を実装する。

## demo と確認方法

Compose で IBM MQ 10.0 の開発用コンテナを起動する。IBM のデフォルト開発設定による `QM1`、`DEV.APP.SVRCONN`、`DEV.QUEUE.1`、`DEV.QUEUE.2` を使う。`mqAppPassword` Compose secret に demo 用パスワードを渡す。[IBM developer default configuration](https://github.com/ibm-messaging/mq-container/blob/master/docs/developer-config.md)。

```powershell
$env:IBMMQ_PASSWORD = Read-Host 'IBM MQ demo password'
New-Item -ItemType Directory -Force demo/docker | Out-Null
Set-Content -NoNewline demo/docker/app-password.txt $env:IBMMQ_PASSWORD
docker compose up -d
mvn -B package
java -jar demo/target/demo-0.1.0-SNAPSHOT.jar
```

demo は起動時に通常テキストと MQMD/PMO カスタマイズ例を送る。二つの listener の受信ログ、MQ 接続再試行、失敗時 backout を確認する。受信メソッドの単体テストだけでなく、実コンテナを使った送受信確認を完了条件とする。
