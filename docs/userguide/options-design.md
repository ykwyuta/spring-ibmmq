# IBM MQ オプション設計ガイド

このガイドは IBM MQ classes for Java の `MQQueueManager`、`MQQueue`、`MQMessage`、`MQPutMessageOptions`、`MQGetMessageOptions` を使う際の選択基準を、このリポジトリの starter と二つの demo に対応づけたものです。対象は Spring Boot 4.1、Java 21、IBM MQ 10.0.x です。API の意味はリンク先の IBM 公式資料、starter 固有の制約は現在の実装に基づきます。基本的な導入と起動は [starter 利用ガイド](README.md)、直接 API を使う例は [native-demo ガイド](native-demo.md)を参照してください。

## 最初に決めること

| 設計上の問い | 選択の目安 | このリポジトリでの設定箇所 |
| --- | --- | --- |
| 処理失敗時に再配送するか | 必要なら syncpoint 付き `@IbmmqListener` または native API を使う | listener は成功時 `commit()`、失敗時 `backout()`。`IbmmqTemplate.receive()` は `MQGMO_NO_SYNCPOINT` |
| キューマネージャーの再起動後もメッセージを保持するか | 必要なら MQMD の `persistence` を `MQPER_PERSISTENT` にする | `IbmmqTemplate.send()` の native overload の `MQMessage`、または native API |
| 送信を作業単位として確定するか | 必要なら `MQPMO_SYNCPOINT` を指定する | template はその送信の直後に `commit()`。複数操作を同一作業単位にする場合は native API |
| 本文の形式と文字コードは何か | 送受信双方で `format`、CCSID、実際のバイト列を一致させる | 簡易送信は `MQFMT_STRING` / CCSID 1208 / UTF-8。異なる形式は `MQMessage` と converter を設定 |
| 入力を共有・排他・参照のどれにするか | キュー既定、明示共有、排他、browse から選ぶ | starter の open オプションは固定。変更が必要なら `IbmmqConnectionFactory` で native API を使う |

永続性と syncpoint は別の設定です。永続メッセージは障害時の保持、syncpoint は MQ 操作の確定・取消しを制御します。[IBM: Message persistence](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=messages-message-persistence)、[IBM: Syncpoint considerations](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=work-syncpoint-considerations-in-mq-applications)

## 接続オプションを設計する

`IbmmqConnectionFactory` は接続ごとにプロパティ表を作り、`host`、`port`、`channel`、クライアント transport、任意の `user` と `password` を設定して `new MQQueueManager(queueManager, properties)` を呼びます。追加の接続設定には `IbmmqConnectionPropertiesCustomizer` を使います。このプロパティ表は接続ごとに独立しています。IBM は接続プロパティ表の設定が `MQEnvironment` の static 設定より優先されることを説明しています。[IBM: MQEnvironment](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqenvironment)

```java
@Bean
IbmmqConnectionPropertiesCustomizer connectionOptions() {
    return properties -> {
        properties.put(MQConstants.HOST_NAME_PROPERTY, "mq.example.com");
        properties.put(MQConstants.PORT_PROPERTY, 1414);
    };
}
```

TLS の cipher suite、証明書ストアなど、クライアント接続固有の項目も IBM が定義する接続プロパティで設定します。チャネル側の TLS 設定と対になる値を確認してください。カスタマイザは初期設定の**後**に実行されるため、同じキーに `put` すると `application.yaml` の値を上書きします。[IBM: MQEnvironment](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqenvironment)

listener はワーカーごとに接続を持ち、`IbmmqTemplate` は送信専用接続を保持します。template の同期受信は操作ごとに別接続を作ります。IBM は一つの `MQQueueManager` を複数スレッドで共用した場合の MQ 呼出しの直列化を説明しており、待機中の MQGET が他の操作に影響し得ます。template は送信を直列化して syncpoint を守り、待機する受信とは接続を分けます。[IBM: MQQueueManager](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqqueuemanager)

IBM MQ classes for Java は IBM MQ クライアントの**自動再接続**をサポートしません。starter の listener と `native-demo listen` は、それぞれアプリケーション側で例外後に接続とキューハンドルを作り直します。template は送信エラーで保持中の接続を破棄し、**次の送信**で再接続します。`native-demo session` も複数コマンドで接続を再利用し、MQエラー後の次の入力で作り直します。失敗した送信や同期受信の再実行は呼び出し元が判断します。再送する際は、送信結果が不明な障害でも重複を処理できるよう、業務 ID などで冪等性を設計してください。[IBM: Automatic client reconnection](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=restart-automatic-client-reconnection)

## MQOPEN: キューの開き方

| 処理 | 現在の starter の `accessQueue` オプション | 意味 |
| --- | --- | --- |
| template 送信 | `MQOO_OUTPUT \| MQOO_FAIL_IF_QUIESCING` | 送信用。キューマネージャー終了中の操作を拒否 |
| template 同期受信 | `MQOO_INPUT_AS_Q_DEF \| MQOO_FAIL_IF_QUIESCING` | キュー定義の共有・排他既定値に従う |
| listener 受信 | `MQOO_INPUT_AS_Q_DEF \| MQOO_FAIL_IF_QUIESCING` | 各ワーカーが同じ既定方式で開く |

`MQOO_INPUT_AS_Q_DEF` の共有・排他はキューの `DEFSOPT` によって決まります。複数ワーカーを利用するなら、実際のキュー定義で共有入力できるかを確認してください。明示的な `MQOO_INPUT_SHARED`、`MQOO_INPUT_EXCLUSIVE`、`MQOO_BROWSE`、read-ahead、context 付き open は starter の template/listener から変更できません。必要な操作は `IbmmqConnectionFactory.createConnection()` で接続を取得し、`accessQueue` を直接呼びます。入力の `AS_Q_DEF`、`SHARED`、`EXCLUSIVE` を同時指定してはいけません。[IBM: MQOPEN](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=calls-mqopen-open-object)、[IBM: Rules for validating MQI options](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=reference-rules-validating-mqi-options)

## MQMD: メッセージ単位の設計

`MQMessage` は MQMD と本文を保持します。`IbmmqTemplate.send(queue, String)` と `send(queue, byte[])` は本文を UTF-8 とみなし、`format=MQFMT_STRING`、`characterSet=1208` を設定します。任意のバイナリ、他の CCSID、永続性、優先順位、期限、メッセージ ID、相関 ID、返信先を扱う場合は native overload で `MQMessage` の各フィールドを設定します。[IBM: MQMessage](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqmessage)

```java
ibmmqTemplate.send("DEV.QUEUE.2", message -> {
    message.format = MQConstants.MQFMT_STRING;
    message.characterSet = 1208;
    message.persistence = MQConstants.MQPER_PERSISTENT;
    message.priority = 5;
    message.expiry = 300; // 30 秒。MQMD の単位は 0.1 秒
    try {
        message.write("注文-123".getBytes(StandardCharsets.UTF_8));
    } catch (IOException ex) {
        throw new UncheckedIOException(ex);
    }
}, options -> options.options |= MQConstants.MQPMO_SYNCPOINT);
```

この断片には `MQConstants`、`StandardCharsets`、`IOException`、`UncheckedIOException` の import が必要です。`expiry` の単位はミリ秒ではなく 0.1 秒です。`persistence` を指定しない場合はキューの既定値に依存するため、配送要件がある送信では値を明示してください。[IBM: MQMD](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqmd)、[IBM: Message persistence](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=messages-message-persistence)、[IBM: Expiry](https://www.ibm.com/docs/en/ibm-mq/9.3.x?topic=descriptor-expiry-mqlong-mqmd)

## MQPMO: 送信をどう確定するか

`MQPutMessageOptions.options` はビットマスクです。`MQPMO_SYNCPOINT` と `MQPMO_NO_SYNCPOINT` は互いに排他的です。native overload で `MQPMO_SYNCPOINT` を指定すると、この starter は MQPUT 後に**その接続で直ちに** `commit()` し、例外時に `backout()` を試みます。指定しなければ template は commit を呼びません。業務処理と複数の MQPUT/MQGET を一つの作業単位にまとめる API は提供していません。[IBM: MQPutMessageOptions](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqputmessageoptions)、[IBM: Rules for validating MQI options](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=reference-rules-validating-mqi-options)

| 要件 | 選択 |
| --- | --- |
| 一件をその場で送る | 簡易 `send` または native overload。永続性は MQMD で別途決める |
| 一件を syncpoint で送る | native overload で `MQPMO_SYNCPOINT` を指定する |
| 複数件の送受信を一括確定する | native API で同じ `MQQueueManager` を使い、全操作後に `commit()`、失敗時に `backout()` |
| context や ID の生成方法を変える | IBM の MQPMO/MQMD の組合せと権限を確認し、native overload または native API を使う |

オプションを追加するときは既存のビットを保つ `|=` が基本です。排他的な値を切り替えるときは両方を残さないよう、全ビットの組合せを確認してください。不正な組合せは `MQRC_OPTIONS_ERROR` になります。[IBM: Rules for validating MQI options](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=reference-rules-validating-mqi-options)

## MQGMO: 待機、選択、再配送

| 受信 API | 初期オプション | 確定タイミング | 追加設定 |
| --- | --- | --- | --- |
| `@IbmmqListener` | `MQGMO_WAIT \| MQGMO_SYNCPOINT \| MQGMO_FAIL_IF_QUIESCING` | メソッド成功時 commit、失敗時 backout | `getOptionsCustomizer` bean。`MQGMO_SYNCPOINT` と有限の非負 `waitInterval` は必須 |
| `IbmmqTemplate.receive()` / `receiveAndConvert()` | `MQGMO_NO_SYNCPOINT` と `WAIT` または `NO_WAIT` | MQGET 時点で除去。後続処理の失敗では戻らない | `timeoutMillis` のみ。GMO カスタマイザは適用されない |
| `native-demo` | `MQGMO_SYNCPOINT` と `WAIT` または `NO_WAIT`、`FAIL_IF_QUIESCING` | 本文読取り後 commit、失敗時 backout | Java コードで MQGMO を直接変更できる |

`MQGMO_WAIT` では、条件に合うメッセージがまだなければ `waitInterval` ミリ秒まで待ちます。`0` を渡す template と native-demo は `MQGMO_NO_WAIT` を選びます。listener の `waitInterval=0` は有限の即時ポーリングとなるため、通常は正の値にします。`MQGMO_NO_WAIT` はゼロ値なので、既存の `MQGMO_WAIT` に `|= MQGMO_NO_WAIT` を加えても WAIT は解除されません。[IBM: MQGetMessageOptions](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqgetmessageoptions)、[IBM: MQGMO field details](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=options-field-details-mqgmo)

listener のカスタマイザは **各 MQGET の直前**に新しい `MQGetMessageOptions` に適用されます。たとえば待ち時間だけを調整する場合は次のようにします。

```java
@Bean("shortPoll")
IbmmqGetOptionsCustomizer shortPoll() {
    return options -> options.waitInterval = 500;
}

@IbmmqListener(value = "DEV.QUEUE.1", getOptionsCustomizer = "shortPoll")
public void onMessage(MQMessage message) {
    // 本文と MQMD を処理
}
```

IBM の `matchOptions` は MsgId/CorrelId などを使った選択を可能にします。ただし現在の listener は MQGET ごとに空の `MQMessage` を作り、カスタマイザには **GMO だけ**を渡します。選択用の MQMD を事前に設定する拡張点はありません。特定 ID の受信には native API で MQMD と `matchOptions` の両方を設定してください。[IBM: MQGMO field details](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=options-field-details-mqgmo)

### `MQGMO_CONVERT` と文字コード

IBM の `MQGMO_CONVERT` は、MQGET 時の MQMD に指定した CCSID/encoding とメッセージ側の値が異なり、形式が変換可能な場合に、本文変換を要求します。変換後のバイト列を無条件に UTF-8 として読む設計は避け、取得した MQMD の `characterSet` と本文の実際の符号化を合わせて読み取ってください。starter の既定 `SimpleIbmmqMessageConverter` は `String` を常に UTF-8 で復号します。この converter と `MQGMO_CONVERT` を組み合わせる場合は、受信本文が UTF-8 になる条件を確認するか、CCSID に対応した独自 converter を指定します。[IBM: Application data conversion](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=queue-application-data-conversion)、[IBM: MQMessage](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqmessage)

同梱の `native-demo` は UTF-8 バイト列をそのまま復号するため `MQGMO_CONVERT` を指定しません。異なる文字コードの送信元を扱うときは、MQMD とデータ形式を確認した上で変換方針を決めてください。

## 失敗、backout、退避先

listener でメソッドが失敗すると既定では `backout()` により再配送されます。`IbmmqListenerErrorHandler` が `DISCARD` を返すと `commit()` して元キューから除去します。`DISCARD` は別キューへの保存ではありません。`MQMessage.backoutCount` を使う場合は、連続失敗時の監視と退避の方針をアプリで設計してください。[IBM: MQMessage](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=java-mqmessage)

キューの `BOTHRESH` と `BOQNAME` は退避閾値と退避先の属性ですが、IBM の説明ではキューマネージャー自体はそれらの値に基づいてメッセージを移動しません。JMS classes はこれらを利用しますが、この starter は JMS を使わず、自動退避も実装していません。退避する場合は native API で同一 syncpoint 内に退避先への put と元キューの get を含める設計を検討してください。[IBM: ALTER queues](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=reference-alter-queues-alter-queue-settings)、[IBM: Syncpoint considerations](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=work-syncpoint-considerations-in-mq-applications)

## 実装時の確認項目

1. 接続先、チャネル、認証、必要なら TLS プロパティを決める。
2. キューの `DEFSOPT` とワーカー数、送受信の `MQOO_*` を合わせる。
3. MQMD の `format`、CCSID、バイト列、永続性、期限、相関 ID を送受信契約として決める。
4. `MQPMO_*` / `MQGMO_*` の syncpoint と、どの処理までを一回の作業単位にするかを決める。
5. 受信失敗時の backout、重複処理、最大再試行回数、退避先を決める。
6. 最後に [IBM の MQI オプション検証規則](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=reference-rules-validating-mqi-options)でビットの組合せを確認する。
