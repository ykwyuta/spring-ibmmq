package com.example;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** IBM MQ classes for Javaだけで送信・受信するコマンドラインdemo。 */
public final class NativeMqDemo {
    private static final String DEFAULT_BODY = "Hello from native IBM MQ";
    private final NativeMqConfig config;

    /**
     * 実行時のMQ接続設定を受け取る。
     *
     * @param config 接続先とキュー名
     */
    public NativeMqDemo(NativeMqConfig config) {
        this.config = config;
    }

    /**
     * 環境変数を読み込み、送信・受信コマンドを実行する。
     *
     * @param args {@code send [本文]}、{@code receive}、{@code roundtrip [本文]} のいずれか
     * @throws MQException IBM MQの接続または操作に失敗した場合
     * @throws IOException メッセージ本文の読書きに失敗した場合
     */
    public static void main(String[] args) throws MQException, IOException {
        new NativeMqDemo(NativeMqConfig.fromEnvironment(System.getenv())).execute(args, System.out);
    }

    /**
     * 指定されたコマンドを一つのMQ接続で実行する。
     *
     * @param args 実行コマンドと任意の本文
     * @param output 結果の出力先
     * @throws MQException IBM MQの接続または操作に失敗した場合
     * @throws IOException メッセージ本文の読書きに失敗した場合
     */
    public void execute(String[] args, PrintStream output) throws MQException, IOException {
        String command = args.length == 0 ? "roundtrip" : args[0];
        if (!command.equals("send") && !command.equals("receive") && !command.equals("roundtrip")) {
            throw new IllegalArgumentException("Use send [text], receive, or roundtrip [text]");
        }
        MQQueueManager manager = new MQQueueManager(config.queueManager(), config.connectionProperties());
        try {
            if (!command.equals("receive")) {
                String body = args.length > 1 ? args[1] : DEFAULT_BODY;
                send(manager, body);
                output.println("Sent: " + body);
            }
            if (!command.equals("send")) {
                String received = receive(manager);
                output.println(received == null ? "No message received" : "Received: " + received);
            }
        } finally {
            manager.disconnect();
        }
    }

    /**
     * MQMDとMQPMOを設定し、syncpoint付きで一件送信する。
     *
     * @param manager 使用するキューマネージャー接続
     * @param body UTF-8の本文
     * @throws MQException MQOPEN、MQPUT、commit、backoutに失敗した場合
     * @throws IOException MQMessageへの書込みに失敗した場合
     */
    private void send(MQQueueManager manager, String body) throws MQException, IOException {
        MQQueue queue = manager.accessQueue(config.queue(),
                MQConstants.MQOO_OUTPUT | MQConstants.MQOO_FAIL_IF_QUIESCING);
        try {
            MQMessage message = new MQMessage();
            message.format = MQConstants.MQFMT_STRING;
            message.characterSet = 1208;
            message.persistence = MQConstants.MQPER_PERSISTENT;
            MQPutMessageOptions options = new MQPutMessageOptions();
            options.options = MQConstants.MQPMO_SYNCPOINT | MQConstants.MQPMO_FAIL_IF_QUIESCING;
            try {
                message.write(body.getBytes(StandardCharsets.UTF_8));
                queue.put(message, options);
                manager.commit();
            } catch (MQException | IOException ex) {
                manager.backout();
                throw ex;
            }
        } finally {
            queue.close();
        }
    }

    /**
     * MQGMOを設定して一件受信し、本文の読取り後にcommitする。
     *
     * @param manager 使用するキューマネージャー接続
     * @return 受信したUTF-8本文。タイムアウト時は{@code null}
     * @throws MQException MQOPEN、MQGET、commit、backoutに失敗した場合
     * @throws IOException MQMessageの読取りに失敗した場合
     */
    private String receive(MQQueueManager manager) throws MQException, IOException {
        MQQueue queue = manager.accessQueue(config.queue(),
                MQConstants.MQOO_INPUT_AS_Q_DEF | MQConstants.MQOO_FAIL_IF_QUIESCING);
        try {
            MQGetMessageOptions options = new MQGetMessageOptions();
            options.options = MQConstants.MQGMO_SYNCPOINT | MQConstants.MQGMO_FAIL_IF_QUIESCING
                    | (config.waitMillis() == 0 ? MQConstants.MQGMO_NO_WAIT : MQConstants.MQGMO_WAIT);
            options.waitInterval = config.waitMillis();
            MQMessage message = new MQMessage();
            try {
                queue.get(message, options);
            } catch (MQException ex) {
                if (ex.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) return null;
                throw ex;
            }
            try {
                byte[] body = new byte[message.getDataLength()];
                message.readFully(body);
                manager.commit();
                return new String(body, StandardCharsets.UTF_8);
            } catch (MQException | IOException ex) {
                manager.backout();
                throw ex;
            }
        } finally {
            queue.close();
        }
    }
}
