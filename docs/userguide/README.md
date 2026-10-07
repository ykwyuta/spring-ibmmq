# IBM MQ starter 利用ガイド

このガイドは `spring-boot-starter-ibmmq` 0.1.0-SNAPSHOT の実装に対応します。Spring Boot 4.1、Java 21、IBM MQ classes for Java を前提に、JMS を使わずにキューへ送受信する方法を説明します。

## 目次

1. [最短で動かす](#最短で動かす)
2. [自分のアプリへ導入する](#自分のアプリへ導入する)
3. [接続設定](#接続設定)
4. [送信する](#送信する)
5. [受信する](#受信する)
6. [IBM MQ 固有の設定を使う](#ibm-mq-固有の設定を使う)
7. [確定・再配送・接続断](#確定再配送接続断)
8. [運用上の注意](#運用上の注意)
9. [トラブルシューティング](#トラブルシューティング)

## 最短で動かす

リポジトリのルートで Java 21、Maven、Docker Compose を利用できるようにします。Compose は IBM MQ Advanced for Developers `10.0.0.0-r4` を起動し、`QM1` と開発用の `DEV.*` キューを使います。開発用コンテナのライセンス条件を確認してください。

```powershell
$env:IBMMQ_PASSWORD = Read-Host 'IBM MQ demo password'
New-Item -ItemType Directory -Force demo/docker | Out-Null
Set-Content -NoNewline demo/docker/app-password.txt $env:IBMMQ_PASSWORD
docker compose up -d
docker compose ps
mvn -B package
java -jar demo/target/demo-0.1.0-SNAPSHOT.jar
```

送信せずに listener だけ起動する場合は `java -jar demo/target/demo-0.1.0-SNAPSHOT.jar --receive-only` を使います。

demo は起動時に次の2件を送信し、`@IbmmqListener` で受信します。

| キュー | 送信方法 | 受信方法 |
| --- | --- | --- |
| `DEV.QUEUE.1` | `ibmmqTemplate.send(queue, String)` | `String` 引数、ワーカー2本 |
| `DEV.QUEUE.2` | MQMD と MQPMO を設定する `send` | `MQMessage` 引数、MQGMO カスタマイザ |

ログに `Received text: Hello from IbmmqTemplate` と `Received native message: ...` が出れば送受信できています。非 Web アプリでも listener が常駐するので、終了するときは `Ctrl+C` を押します。コンテナを止めるときは `docker compose down` を実行します。データボリュームは残ります。

初回はイメージの取得とキューマネージャーの作成に時間がかかります。MQ の準備前に demo を起動すると、listener は再接続しますが起動時の送信は失敗するため、demo を再実行してください。

demo の設定は [`demo/src/main/resources/application.yaml`](../../demo/src/main/resources/application.yaml)、送受信コードは [`DemoApplication`](../../demo/src/main/java/com/example/DemoApplication.java) と [`DemoReceiver`](../../demo/src/main/java/com/example/DemoReceiver.java) にあります。

## 自分のアプリへ導入する

このリポジトリの snapshot を別の Maven プロジェクトから使う場合、先にルートで `mvn install` します。公開リポジトリへはまだ配布していません。

```powershell
mvn -B install
```

Spring Boot 4.1 のアプリに次の依存を追加します。starter が IBM MQ の `com.ibm.mq.allclient` を取り込みます。`javax.jms-api` は依存から除外しており、starter の送受信処理に JMS API は使いません。

```xml
<dependency>
    <groupId>io.github.spring-ibmmq</groupId>
    <artifactId>spring-boot-starter-ibmmq</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Maven groupId は `io.github.spring-ibmmq` です。Java の package 名にはハイフンを使えないため、import は `io.github.spring_ibmmq` を使用します。通常は `@SpringBootApplication` だけで自動構成され、`@EnableIbmmq` などの追加アノテーションは不要です。`IbmmqTemplate`、`IbmmqConnectionFactory`、listener registry が bean として登録されます。

## 接続設定

アプリの `application.yaml` に接続先を記述します。下記は同梱の Compose 用です。

```yaml
ibmmq:
  host: localhost
  port: 1414
  channel: DEV.APP.SVRCONN
  queue-manager: QM1
  user: app
  password: ${IBMMQ_PASSWORD}
  wait-interval: 1000
  reconnect-delay: 5000
  failure-delay: 1000
```

ローカル demo では、環境変数 `IBMMQ_PASSWORD` と Compose secret のファイル `demo/docker/app-password.txt` に同じ値を設定します。パスワードファイルは Git 管理対象外です。実環境では秘密情報管理機構から渡してください。

| 設定名 | 既定値 | 内容 |
| --- | --- | --- |
| `ibmmq.enabled` | `true` | `false` にすると starter の自動構成を無効化 |
| `ibmmq.host` | `localhost` | MQ サーバーのホスト |
| `ibmmq.port` | `1414` | MQ listener のポート |
| `ibmmq.channel` | `DEV.APP.SVRCONN` | クライアント接続チャネル |
| `ibmmq.queue-manager` | `QM1` | キューマネージャー名 |
| `ibmmq.user` | `app` | 接続ユーザー。空欄なら明示設定しない |
| `ibmmq.password` | 空文字 | 接続パスワード。空欄なら明示設定しない |
| `ibmmq.wait-interval` | `1000` ms | listener の MQGET 待ち時間 |
| `ibmmq.reconnect-delay` | `5000` ms | 接続失敗後の再試行までの待ち時間 |
| `ibmmq.failure-delay` | `1000` ms | メソッド失敗、backout 後の待ち時間 |

`reconnect-delay` と `failure-delay` はミリ秒単位です。負の値は実装上 `0` として待機します。`wait-interval` は負にできず、listener ごとに上書きできます。`0` は待たずに取得を繰り返すため、通常は正の値を指定してください。

## 送信する

### UTF-8 の文字列

`IbmmqTemplate` をコンストラクタで受け取ります。Spring の bean 名は `ibmmqTemplate` です。

```java
package com.example;

import io.github.spring_ibmmq.IbmmqTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderPublisher {
    private final IbmmqTemplate ibmmqTemplate;

    public OrderPublisher(IbmmqTemplate ibmmqTemplate) {
        this.ibmmqTemplate = ibmmqTemplate;
    }

    public void publish(String orderId) {
        ibmmqTemplate.send("DEV.QUEUE.1", orderId);
    }
}
```

この overload は文字列を UTF-8 バイト列に変換し、MQMD の `format=MQFMT_STRING`、`characterSet=1208` を設定します。`send(queue, byte[])` も同じ MQMD を設定するため、UTF-8 のテキストデータ向けです。任意のバイナリデータには次の native overload を使って `format` を自分で指定してください。

### MQMD と MQPMO を細かく設定する

```java
import com.ibm.mq.constants.MQConstants;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

ibmmqTemplate.send("DEV.QUEUE.2", message -> {
    message.format = MQConstants.MQFMT_STRING;
    message.characterSet = 1208;
    message.persistence = MQConstants.MQPER_PERSISTENT;
    message.priority = 5;
    try {
        message.write("important".getBytes(StandardCharsets.UTF_8));
    } catch (java.io.IOException ex) {
        throw new UncheckedIOException(ex);
    }
}, options -> options.options |= MQConstants.MQPMO_FAIL_IF_QUIESCING);
```

第2引数で `MQMessage` の本文、MQMD、メッセージプロパティを、第3引数で `MQPutMessageOptions` を変更できます。`MQPMO_SYNCPOINT` を指定した場合、この `send` は MQPUT 成功後に commit し、失敗時に backout を試みます。`MQException` は `IbmmqException` に包まれます。呼び出しごとに新しい MQ 接続とキューハンドルを作る実装です。

### 変換して送信する

`convertAndSend(queue, payload)` は登録済みの `IbmmqMessageConverter` を使います。既定の `SimpleIbmmqMessageConverter` は `String` と `byte[]` を UTF-8 テキスト形式で書き込みます。独自の Java オブジェクトを扱う場合は変換器を bean として登録します。

```java
ibmmqTemplate.convertAndSend("DEV.QUEUE.1", "converted text");
```

### 同期的に1件受信する

Spring AMQP の `receive` / `receiveAndConvert` に相当する API です。待ち時間の単位はミリ秒で、`0` は即時取得です。タイムアウト時は `null` を返します。

```java
MQMessage nativeMessage = ibmmqTemplate.receive("DEV.QUEUE.1", 1000);
String text = ibmmqTemplate.receiveAndConvert("DEV.QUEUE.1", 1000, String.class);
```

この同期受信は MQGMO_NO_SYNCPOINT でキューから取り除きます。受け取った後のアプリ処理に失敗しても自動的には戻りません。処理成功時に確定したい場合は `@IbmmqListener` を使うか、`IbmmqConnectionFactory` から取得した native 接続で syncpoint を管理してください。

## 受信する

Spring bean の public メソッドに `@IbmmqListener` を付けます。既定の変換器では引数は **`String`、`byte[]`、`MQMessage` のいずれか1個**です。独自変換器を使えば別の型も指定できます。戻り値は使いません。

```java
package com.example;

import io.github.spring_ibmmq.IbmmqListener;
import org.springframework.stereotype.Component;

@Component
public class OrderListener {
    @IbmmqListener(value = "DEV.QUEUE.1", concurrency = 2, waitInterval = 2000)
    public void onOrder(String orderId) {
        System.out.println("order=" + orderId);
    }
}
```

| アノテーション属性 | 既定値 | 内容 |
| --- | --- | --- |
| `value` | 必須 | 受信するキュー名 |
| `concurrency` | `1` | そのメソッドのワーカー数。1以上 |
| `waitInterval` | `-1` | `-1` は `ibmmq.wait-interval` を使用。それ以外は MQGET の待ち時間 (ms) |
| `getOptionsCustomizer` | 空文字 | `IbmmqGetOptionsCustomizer` bean の名前 |
| `messageConverter` | 空文字 | 既定に代えて使用する `IbmmqMessageConverter` bean の名前 |
| `errorHandler` | 空文字 | 失敗時に呼ぶ `IbmmqListenerErrorHandler` bean の名前 |

`concurrency=2` は、同じキューに対し独立した MQ 接続とキューハンドルを持つワーカーを2本作ります。処理順序はワーカー間で保証されないため、順序が必要な処理は `concurrency=1` にしてください。アノテーションのキュー名は現在、固定文字列です。

`String` 引数は本文全体を UTF-8 としてデコードします。`byte[]` は本文をそのまま渡します。MQMD や IBM MQ のメッセージプロパティが必要なら `MQMessage` を受け取ります。

```java
import com.ibm.mq.MQMessage;
import io.github.spring_ibmmq.IbmmqListener;
import java.io.IOException;

@IbmmqListener("DEV.QUEUE.2")
public void onNativeMessage(MQMessage message) throws IOException {
    int persistence = message.persistence;
    String body = message.readStringOfByteLength(message.getDataLength());
    System.out.println(persistence + ": " + body);
}
```

`MQMessage` は受信処理中のオブジェクトです。読み取り位置が進むため、本文を複数回読む場合は位置を管理してください。メソッド終了後に保持して非同期処理へ渡す使い方は避けてください。処理が正常に終わった時点で MQGET が commit されます。

### 独自型への変換

`IbmmqMessageConverter` を bean にすると、`convertAndSend` と listener の変換に利用できます。listener だけ別の変換器にする場合は `@IbmmqListener(messageConverter="beanName")` で名前を指定します。変換器は `write`、`read`、`supportsRead` を実装します。

```java
@Bean
IbmmqMessageConverter orderConverter() {
    return new IbmmqMessageConverter() {
        private final SimpleIbmmqMessageConverter text = new SimpleIbmmqMessageConverter();

        public void write(Object payload, MQMessage message) throws IOException {
            text.write(((Order) payload).id(), message);
        }

        public Object read(MQMessage message, Class<?> targetType) throws IOException {
            return new Order((String) text.read(message, String.class));
        }

        public boolean supportsRead(Class<?> targetType) {
            return targetType == Order.class;
        }
    };
}
```

```java
@IbmmqListener(value = "DEV.QUEUE.1", messageConverter = "orderConverter")
public void onOrder(Order order) { /* 処理 */ }
```

`Order` は利用側で定義する型です。この例では ID だけを本文にします。必要に応じて JSON などの形式を自分の変換器で実装してください。bean を一つだけ登録すると template と listener の既定変換器にもなります。

### 失敗したメッセージの処遇

`@IbmmqListener(errorHandler="beanName")` に `IbmmqListenerErrorHandler` bean を指定できます。戻り値が `REQUEUE` なら backout、`DISCARD` なら commit してキューから取り除きます。指定がなければ `REQUEUE` です。

```java
@Bean
IbmmqListenerErrorHandler discardInvalidMessage() {
    return (message, failure) -> {
        // 破棄の判断と監査ログの記録は利用側の責任
        return IbmmqFailureAction.DISCARD;
    };
}
```

`DISCARD` は元キューから削除する処理です。別キューへの移送は行いません。エラーハンドラ自体が例外を投げた場合は `REQUEUE` として扱います。

## IBM MQ 固有の設定を使う

### MQGET オプション

`IbmmqGetOptionsCustomizer` bean を作り、名前をアノテーションで指定します。カスタマイザは各 MQGET の直前に呼ばれます。

```java
import com.ibm.mq.constants.MQConstants;
import io.github.spring_ibmmq.IbmmqGetOptionsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MqOptionsConfig {
    @Bean
    IbmmqGetOptionsCustomizer convertOnGet() {
        return options -> options.options |= MQConstants.MQGMO_CONVERT;
    }
}
```

```java
@IbmmqListener(value = "DEV.QUEUE.2", getOptionsCustomizer = "convertOnGet")
public void onMessage(MQMessage message) {
    // MQMD と本文を使用
}
```

starter は `MQGMO_WAIT | MQGMO_SYNCPOINT | MQGMO_FAIL_IF_QUIESCING` を初期設定します。カスタマイザで追加・変更できますが、`MQGMO_SYNCPOINT` は必須で、`waitInterval` は0以上の有限値が必要です。MQ オプションの有効な組み合わせは IBM MQ の仕様に従います。

### 接続プロパティ

`IbmmqConnectionPropertiesCustomizer` bean は、各接続の直前に `Hashtable<String, Object>` を受け取ります。host、port、channel、認証情報、transport の標準設定が入った後で呼ばれます。

```java
import com.ibm.mq.constants.MQConstants;
import io.github.spring_ibmmq.IbmmqConnectionPropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MqConnectionConfig {
    @Bean
    IbmmqConnectionPropertiesCustomizer connectionCustomizer() {
        return properties -> properties.put(MQConstants.HOST_NAME_PROPERTY, "mq.example.com");
    }
}
```

複数 bean は Spring の順序に従って適用されます。TLS、CCDT、チャネル出口などに必要なキーと値は、使用中の IBM MQ client の資料を確認して指定してください。`MQEnvironment` の静的設定は変更しません。より細かい MQ API が必要なら `IbmmqConnectionFactory#createConnection()` で `MQQueueManager` を取得し、作成した側でキューハンドルと接続を閉じてください。

## 確定・再配送・接続断

listener は `MQGMO_SYNCPOINT` を付けて MQGET します。受信メソッドが正常終了すると `MQQueueManager.commit()` します。例外時は既定で `backout()` し、`ibmmq.failure-delay` の後に受信を再開します。指定したエラーハンドラが `DISCARD` を返した場合は commit します。接続障害などで MQ 操作が失敗した場合は接続を閉じ、`ibmmq.reconnect-delay` の後に新しい接続を作ります。

同じメッセージが再配送される可能性があります。メソッドの副作用は重複しても安全になるよう設計してください。DB 更新と MQ の commit は、この starter では一つの原子的なトランザクションになりません。また、listener から `IbmmqTemplate` で送信しても、別の MQ 接続を使うため受信側と同じ syncpoint には入りません。

処理に失敗し続けるメッセージは繰り返し配送されます。現在の starter は backout 回数を見て別キューへ移す機能を持ちません。`failure-delay` は再試行間隔であり、隔離・最大再試行回数ではありません。本番利用では、失敗メッセージの隔離と再処理の方針を別途用意してください。

## 運用上の注意

- **接続数:** listener はワーカーごとに1接続を保持します。送信は呼び出しごとに接続を作成します。`concurrency` と送信量に応じて MQ の接続上限を見積もってください。
- **文字コード:** 簡易送信と `String` 受信は UTF-8 前提です。別の CCSID や独自フォーマットは `MQMessage` を用い、自分で読み書きを制御してください。
- **キューの準備:** starter はキューやチャネルを作成しません。Compose の `MQ_DEV=true` では demo 用の `DEV.QUEUE.1` などが用意されます。
- **設定変更:** listener のキュー名やワーカー数を稼働中に動的変更する API はありません。設定を変えた場合はアプリを再起動してください。
- **対象範囲:** トピック購読、接続プール、XA、backout queue 自動移送、監視メトリクスは未実装です。

## トラブルシューティング

| 症状 | 確認すること |
| --- | --- |
| demo 起動時に `MQPUT failed` | `docker compose ps` で MQ が起動しているか確認。初回起動中なら準備完了後に demo を再実行 |
| listener が接続失敗ログを繰り返す | `host`、`port`、`queue-manager`、`channel`、MQ サーバー側の listener を確認 |
| `MQRC_NOT_AUTHORIZED` (`2035`) | `user`、`password`、チャネル認証、キューの get/put 権限を確認。Compose demo では `app` と `IBMMQ_PASSWORD` の設定値 |
| キューが見つからない (`2085`) | キュー名とキューマネージャー名、キューの作成状態を確認 |
| 日本語などが文字化けする | 送信側の CCSID と本文エンコーディングを確認。簡易 API は UTF-8 固定 |
| 同じメッセージを何度も受ける | listener の例外ログを確認。失敗時は backout され再配送される |
| 受信メソッドが登録されない | Spring bean か、public メソッドか、引数が対応する1個の型かを確認 |

IBM MQ のオプションや理由コードの詳細は [MQGetMessageOptions](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqgetmessageoptions)、[MQQueueManager](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqqueuemanager) の資料を参照してください。設計上の判断は [提案書](../proposal/ibmmq-starter.md) にあります。

Spring AMQP の API との対応関係は [比較表](spring-amqp-comparison.md) にまとめています。
