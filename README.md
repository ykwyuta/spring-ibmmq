# spring-ibmmq

Spring Boot 4.1 用の IBM MQ Java base classes starter と送受信 demo です。JMS API は使用しません。Maven の groupId は `io.github.spring-ibmmq`、Java のパッケージはハイフンが使えないため `io.github.spring_ibmmq` です。

`demo` は今回の starter を使う Spring Boot アプリ、`native-demo` は starter に依存せず `com.ibm.mq` の Java API を直接呼ぶコマンドラインアプリです。

## 動かし方

Java 21、Maven、Docker Compose が必要です。IBM MQ Advanced for Developers イメージは開発用途のライセンスです。ローカルで選んだパスワードを環境変数 `IBMMQ_PASSWORD` に設定し、同じ値を Git 管理対象外の `demo/docker/app-password.txt` に保存してください。

```powershell
$env:IBMMQ_PASSWORD = Read-Host 'IBM MQ demo password'
New-Item -ItemType Directory -Force demo/docker | Out-Null
Set-Content -NoNewline demo/docker/app-password.txt $env:IBMMQ_PASSWORD
docker compose up -d
mvn package
java -jar demo/target/demo-0.1.0-SNAPSHOT.jar
```

起動時に demo は `DEV.QUEUE.1` と `DEV.QUEUE.2` へ送信します。listener の受信ログを確認してください。MQ が起動途中でも listener は接続を再試行しますが、起動時の送信は失敗します。その場合は demo を再実行してください。

素の IBM MQ API による demo は、同じ MQ コンテナと `IBMMQ_PASSWORD` を使って次のように実行します。既定では `DEV.QUEUE.3` に送信してから受信します。

```powershell
java -jar native-demo/target/native-demo-0.1.0-SNAPSHOT.jar
java -jar native-demo/target/native-demo-0.1.0-SNAPSHOT.jar send "sample"
java -jar native-demo/target/native-demo-0.1.0-SNAPSHOT.jar receive
```

コードと設定方法は [native-demo 利用ガイド](docs/userguide/native-demo.md) を参照してください。

## 利用例

starter の Maven 依存を追加すると、`ibmmqTemplate` と listener が自動構成されます。

```xml
<dependency>
  <groupId>io.github.spring-ibmmq</groupId>
  <artifactId>spring-boot-starter-ibmmq</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
@Component
class Orders {
    private final IbmmqTemplate ibmmqTemplate;
    Orders(IbmmqTemplate ibmmqTemplate) { this.ibmmqTemplate = ibmmqTemplate; }

    void send(String text) { ibmmqTemplate.send("DEV.QUEUE.1", text); }

    @IbmmqListener("DEV.QUEUE.2")
    public void receive(String text) { System.out.println(text); }
}
```

接続先は `ibmmq.host`、`port`、`channel`、`queue-manager`、`user`、`password` で設定します。`ibmmq.enabled=false` で自動構成を無効化できます。MQ 固有の設定には `IbmmqConnectionPropertiesCustomizer` bean、送信時の `MQMessage` と `MQPutMessageOptions` カスタマイズ、`@IbmmqListener(getOptionsCustomizer="...")` と `MQMessage` 引数を利用できます。同期受信、独自のメッセージ変換器、listener ごとのエラー処理も利用できます。文字列とバイト配列の簡易 API は UTF-8 を使います。

listener の受信は MQ syncpoint で確定します。メソッドが正常終了すれば commit、例外が出れば既定で backout します。エラーハンドラが `DISCARD` を返した場合は commit します。失敗メッセージの繰り返し配送を止める backout queue への自動移送は未実装です。実運用では再試行・隔離方針を設定してください。送信は呼び出しごとに MQ 接続を作成する初期実装です。

設計、対象範囲、今後の課題は [提案書](docs/proposal/ibmmq-starter.md) を参照してください。

設定項目、送受信 API、MQ 固有オプション、失敗時の挙動については [利用ガイド](docs/userguide/README.md) を参照してください。

`docker compose up -d` の後に `mvn verify` を実行すると、実 MQ を使うテストと starter・2種類の demo の JaCoCo C1（分岐）網羅チェックが走ります。ケース一覧は [テストケース](docs/testcases/README.md) にあります。
