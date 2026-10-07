package io.github.spring_ibmmq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** IBM MQ への接続と listener の待機時間を保持する設定。 */
@ConfigurationProperties(prefix = "ibmmq")
public class IbmmqProperties {
    /** IBM MQ 接続と listener の既定設定を生成する。 */
    public IbmmqProperties() {
    }

    private String host = "localhost";
    private int port = 1414;
    private String channel = "DEV.APP.SVRCONN";
    private String queueManager = "QM1";
    private String user = "app";
    private String password = "";
    private int waitInterval = 1000;
    private long reconnectDelay = 5000;
    private long failureDelay = 1000;

    /** MQ サーバーのホスト名を返す。 @return MQ サーバーのホスト名。 */
    public String getHost() { return host; }
    /** MQ サーバーのホスト名を設定する。 @param host MQ サーバーのホスト名。 */
    public void setHost(String host) { this.host = host; }
    /** MQ listener のポート番号を返す。 @return MQ listener のポート番号。 */
    public int getPort() { return port; }
    /** MQ listener のポート番号を設定する。 @param port MQ listener のポート番号。 */
    public void setPort(int port) { this.port = port; }
    /** クライアント接続チャネル名を返す。 @return クライアント接続チャネル名。 */
    public String getChannel() { return channel; }
    /** クライアント接続チャネル名を設定する。 @param channel クライアント接続チャネル名。 */
    public void setChannel(String channel) { this.channel = channel; }
    /** 接続先キューマネージャー名を返す。 @return 接続先キューマネージャー名。 */
    public String getQueueManager() { return queueManager; }
    /** 接続先キューマネージャー名を設定する。 @param queueManager 接続先キューマネージャー名。 */
    public void setQueueManager(String queueManager) { this.queueManager = queueManager; }
    /** 認証用ユーザー ID を返す。 @return 認証に使用するユーザー ID。 */
    public String getUser() { return user; }
    /** 認証用ユーザー ID を設定する。 @param user 認証に使用するユーザー ID。空欄なら明示設定しない。 */
    public void setUser(String user) { this.user = user; }
    /** 認証用パスワードを返す。 @return 認証に使用するパスワード。 */
    public String getPassword() { return password; }
    /** 認証用パスワードを設定する。 @param password 認証に使用するパスワード。空欄なら明示設定しない。 */
    public void setPassword(String password) { this.password = password; }
    /** MQGET の既定待ち時間を返す。 @return MQGET でメッセージを待つ時間（ミリ秒）。 */
    public int getWaitInterval() { return waitInterval; }
    /** MQGET の既定待ち時間を設定する。 @param waitInterval MQGET でメッセージを待つ時間（ミリ秒）。 */
    public void setWaitInterval(int waitInterval) { this.waitInterval = waitInterval; }
    /** 接続再試行の待機時間を返す。 @return 接続障害後の再試行までの待機時間（ミリ秒）。 */
    public long getReconnectDelay() { return reconnectDelay; }
    /** 接続再試行の待機時間を設定する。 @param reconnectDelay 接続障害後の再試行までの待機時間（ミリ秒）。 */
    public void setReconnectDelay(long reconnectDelay) { this.reconnectDelay = reconnectDelay; }
    /** listener 失敗後の待機時間を返す。 @return listener 処理失敗後の待機時間（ミリ秒）。 */
    public long getFailureDelay() { return failureDelay; }
    /** listener 失敗後の待機時間を設定する。 @param failureDelay listener 処理失敗後の待機時間（ミリ秒）。 */
    public void setFailureDelay(long failureDelay) { this.failureDelay = failureDelay; }
}
