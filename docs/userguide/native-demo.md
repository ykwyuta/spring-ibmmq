# 素の IBM MQ Java API による demo

`native-demo` は `com.ibm.mq:com.ibm.mq.allclient` の `MQQueueManager`、`MQQueue`、`MQMessage` を直接使用するコマンドラインアプリです。今回作成した Spring Boot starter と Spring の実行時ライブラリには依存しません。Spring Boot を使う例は [既存の利用ガイド](README.md)を参照してください。

## 準備と起動

Java 21、Maven、Docker Compose を準備します。リポジトリのルートでパスワードを入力し、Compose secret とアプリの環境変数に同じ値を設定します。パスワードファイルは Git 管理対象外です。

```powershell
$env:IBMMQ_PASSWORD = Read-Host 'IBM MQ demo password'
New-Item -ItemType Directory -Force demo/docker | Out-Null
Set-Content -NoNewline demo/docker/app-password.txt $env:IBMMQ_PASSWORD
docker compose up -d
mvn -B -pl native-demo -am package
java -jar native-demo/target/native-demo-0.1.0-SNAPSHOT.jar
```

引数なしは `roundtrip` と同じです。既定の `DEV.QUEUE.3` へ一件送信してから同じキューを一件受信し、`Sent: ...` と `Received: ...` を表示します。受信待ち時間は既定で3秒です。

```powershell
java -jar native-demo/target/native-demo-0.1.0-SNAPSHOT.jar send "hello"
java -jar native-demo/target/native-demo-0.1.0-SNAPSHOT.jar receive
java -jar native-demo/target/native-demo-0.1.0-SNAPSHOT.jar roundtrip "custom body"
```

`send` は送信のみ、`receive` は受信のみを行います。空キューで待ち時間が過ぎると `No message received` と表示します。終了後に `docker compose down` でコンテナを停止できます。

## 接続設定

| 環境変数 | 既定値 | 用途 |
| --- | --- | --- |
| `IBMMQ_PASSWORD` | なし、必須 | 開発用 `app` ユーザーのパスワード |
| `IBMMQ_HOST` | `localhost` | 接続先ホスト |
| `IBMMQ_PORT` | `1414` | listener ポート |
| `IBMMQ_CHANNEL` | `DEV.APP.SVRCONN` | クライアント接続チャネル |
| `IBMMQ_QUEUE_MANAGER` | `QM1` | キューマネージャー |
| `IBMMQ_USER` | `app` | ユーザー ID |
| `IBMMQ_QUEUE` | `DEV.QUEUE.3` | 送受信先キュー |
| `IBMMQ_WAIT_MILLIS` | `3000` | MQGET の待ち時間。`0` は即時取得 |

接続設定は [NativeMqConfig](../../native-demo/src/main/java/com/example/NativeMqConfig.java) が `Hashtable` に入れ、`new MQQueueManager(queueManager, properties)` へ渡します。送受信処理は [NativeMqDemo](../../native-demo/src/main/java/com/example/NativeMqDemo.java) にあります。`MQEnvironment` の静的設定は変更しません。

## MQ 固有の動作

送信では `MQMessage` の `format=MQFMT_STRING`、`characterSet=1208`、`persistence=MQPER_PERSISTENT` を指定します。`MQPutMessageOptions` には `MQPMO_SYNCPOINT` と `MQPMO_FAIL_IF_QUIESCING` を設定し、MQPUT の後に明示的に `commit()` します。送信中に失敗した場合は `backout()` します。

受信では `MQGetMessageOptions` に `MQGMO_SYNCPOINT`、`MQGMO_FAIL_IF_QUIESCING` と有限の待ち時間を設定します。本文を UTF-8 として読んでから `commit()` します。`MQRC_NO_MSG_AVAILABLE` はタイムアウトとして扱います。受信本文の処理中に失敗した場合は `backout()` し、再配送可能にします。demo は UTF-8 メッセージを前提とするため、MQGET に `MQGMO_CONVERT` は指定しません。IBM MQ による文字コード変換を有効にする場合は、変換後のCCSIDに合わせて本文を解釈してください。

キューは操作ごとに `close()` し、最後にキューマネージャーを `disconnect()` します。接続断からの自動再接続や常駐ポーリングは実装していません。接続プロパティとsyncpointの扱いはIBMの [MQQueueManager](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqqueuemanager) と [MQGMO](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=options-field-details-mqgmo) の資料に従っています。

## テスト

同じ環境変数とComposeコンテナを使い、`mvn -B verify` を実行します。正常系は実IBM MQを使い、MockはMQGET例外の注入にだけ使います。JaCoCoのC1は `BRANCH_MISSED=0` を合格条件とします。詳細は [テストケース](../testcases/README.md) を参照してください。
