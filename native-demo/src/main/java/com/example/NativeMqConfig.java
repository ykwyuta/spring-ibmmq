package com.example;

import com.ibm.mq.constants.MQConstants;
import java.util.Hashtable;
import java.util.Map;

/**
 * Springを使わないIBM MQクライアントの接続先と受信待ち時間。
 *
 * @param host 接続先ホスト
 * @param port 接続先ポート
 * @param channel クライアント接続チャネル
 * @param queueManager キューマネージャー名
 * @param user 認証ユーザー名
 * @param password 認証パスワード
 * @param queue 送受信するキュー名
 * @param waitMillis MQGETの待ち時間（ミリ秒）
 */
public record NativeMqConfig(String host, int port, String channel, String queueManager,
                             String user, String password, String queue, int waitMillis) {
    /**
     * 環境変数から開発用MQの接続設定を作る。
     *
     * @param environment OSの環境変数
     * @return 接続設定
     */
    public static NativeMqConfig fromEnvironment(Map<String, String> environment) {
        String password = environment.get("IBMMQ_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("IBMMQ_PASSWORD must be set");
        }
        int waitMillis = Integer.parseInt(environment.getOrDefault("IBMMQ_WAIT_MILLIS", "3000"));
        if (waitMillis < 0) {
            throw new IllegalArgumentException("IBMMQ_WAIT_MILLIS must be non-negative");
        }
        return new NativeMqConfig(
                environment.getOrDefault("IBMMQ_HOST", "localhost"),
                Integer.parseInt(environment.getOrDefault("IBMMQ_PORT", "1414")),
                environment.getOrDefault("IBMMQ_CHANNEL", "DEV.APP.SVRCONN"),
                environment.getOrDefault("IBMMQ_QUEUE_MANAGER", "QM1"),
                environment.getOrDefault("IBMMQ_USER", "app"), password,
                environment.getOrDefault("IBMMQ_QUEUE", "DEV.QUEUE.3"), waitMillis);
    }

    /**
     * {@code MQQueueManager} のコンストラクタへ渡す接続プロパティを作る。
     * 静的な {@code MQEnvironment} は変更しない。
     *
     * @return IBM MQ native接続用プロパティ
     */
    public Hashtable<String, Object> connectionProperties() {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(MQConstants.HOST_NAME_PROPERTY, host);
        properties.put(MQConstants.PORT_PROPERTY, port);
        properties.put(MQConstants.CHANNEL_PROPERTY, channel);
        properties.put(MQConstants.TRANSPORT_PROPERTY, MQConstants.TRANSPORT_MQSERIES_CLIENT);
        properties.put(MQConstants.USER_ID_PROPERTY, user);
        properties.put(MQConstants.PASSWORD_PROPERTY, password);
        return properties;
    }
}
