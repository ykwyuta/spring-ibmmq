package com.example;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** IBM MQ classes for Javaだけで送信・受信するコマンドラインdemo。 */
public final class NativeMqDemo {
    private static final String DEFAULT_BODY = "Hello from native IBM MQ";
    private final NativeMqConfig config;
    private final ConnectionOpener connectionOpener;
    private volatile boolean listening = true;

    /** テスト時の接続失敗注入にも使う、native接続の生成処理。 */
    @FunctionalInterface
    interface ConnectionOpener {
        /** @return 新しいIBM MQ接続。 @throws MQException 接続失敗時。 */
        MQQueueManager open() throws MQException;
    }

    /**
     * 実行時のMQ接続設定を受け取る。
     *
     * @param config 接続先とキュー名
     */
    public NativeMqDemo(NativeMqConfig config) {
        this.config = config;
        this.connectionOpener = () -> new MQQueueManager(config.queueManager(), config.connectionProperties());
    }

    /**
     * 接続生成処理を受け取る。例外発生後の再接続試験で使用する。
     *
     * @param config 接続先と待ち時間
     * @param connectionOpener native接続の生成処理
     */
    NativeMqDemo(NativeMqConfig config, ConnectionOpener connectionOpener) {
        this.config = config;
        this.connectionOpener = connectionOpener;
    }

    /**
     * 環境変数を読み込み、送信・受信コマンドを実行する。
     *
     * @param args {@code send [本文]}、{@code receive}、{@code roundtrip [本文]}、
     *             {@code listen}、{@code session} のいずれか
     * @throws MQException IBM MQの接続または操作に失敗した場合
     * @throws IOException メッセージ本文の読書きに失敗した場合
     */
    public static void main(String[] args) throws MQException, IOException {
        NativeMqDemo demo = new NativeMqDemo(NativeMqConfig.fromEnvironment(System.getenv()));
        Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            demo.stopListening();
            mainThread.interrupt();
            try {
                mainThread.join(5000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        demo.execute(args, System.out);
    }

    /** 常駐ポーリングを次の確認時に終了させる。 */
    void stopListening() {
        listening = false;
    }

    /**
     * 単発コマンド、または再接続する常駐受信コマンドを実行する。
     *
     * @param args 実行コマンドと任意の本文
     * @param output 結果の出力先
     * @throws MQException IBM MQの接続または操作に失敗した場合
     * @throws IOException メッセージ本文の読書きに失敗した場合
     */
    public void execute(String[] args, PrintStream output) throws MQException, IOException {
        execute(args, new InputStreamReader(System.in, StandardCharsets.UTF_8), output);
    }

    /**
     * 入力元を指定して単発・常駐・対話コマンドを実行する。
     *
     * @param args 実行コマンドと任意の本文
     * @param input 対話モードのコマンド入力元
     * @param output 結果の出力先
     * @throws MQException 単発コマンドのMQ操作に失敗した場合
     * @throws IOException 入力または本文の読書きに失敗した場合
     */
    void execute(String[] args, Reader input, PrintStream output) throws MQException, IOException {
        String command = args.length == 0 ? "roundtrip" : args[0];
        if (!command.equals("send") && !command.equals("receive") && !command.equals("roundtrip")
                && !command.equals("listen") && !command.equals("session")) {
            throw new IllegalArgumentException("Use send [text], receive, roundtrip [text], listen, or session");
        }
        if (command.equals("listen")) {
            listen(output, () -> listening);
            return;
        }
        if (command.equals("session")) {
            session(new BufferedReader(input), output);
            return;
        }
        MQQueueManager manager = connectionOpener.open();
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
     * 一つの接続で複数の送受信コマンドを処理する。
     * MQエラー後のコマンドは新しい接続で処理し、失敗したコマンドは再実行しない。
     *
     * @param input 改行区切りのコマンド
     * @param output コマンド結果の出力先
     * @throws IOException 入力または本文の読書きに失敗した場合
     */
    private void session(BufferedReader input, PrintStream output) throws IOException {
        MQQueueManager manager = null;
        output.println("Session ready: send <text>, receive, quit");
        try {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.equals("quit")) return;
                if (!line.startsWith("send ") && !line.equals("receive")) {
                    output.println("Use send <text>, receive, or quit");
                    continue;
                }
                try {
                    if (manager == null) manager = connectionOpener.open();
                    if (line.startsWith("send ")) {
                        String body = line.substring(5);
                        send(manager, body);
                        output.println("Sent: " + body);
                    } else {
                        String received = receive(manager);
                        output.println(received == null ? "No message received" : "Received: " + received);
                    }
                } catch (MQException ex) {
                    output.println("MQ operation failed (reason " + ex.reasonCode + "); reconnecting on next command");
                    closeQuietly(null, manager);
                    manager = null;
                }
            }
        } finally {
            closeQuietly(null, manager);
        }
    }

    /**
     * キューを常駐ポーリングし、MQ障害時には接続とキューハンドルを作り直す。
     *
     * @param output 受信結果と再接続状態の出力先
     * @param keepRunning 終了要求がない間だけtrueを返す条件
     * @throws IOException 本文の読取りに失敗した場合
     */
    void listen(PrintStream output, BooleanSupplier keepRunning) throws IOException {
        if (config.waitMillis() == 0) {
            throw new IllegalArgumentException("listen requires IBMMQ_WAIT_MILLIS greater than zero");
        }
        while (keepRunning.getAsBoolean()) {
            MQQueueManager manager = null;
            MQQueue queue = null;
            try {
                manager = connectionOpener.open();
                queue = manager.accessQueue(config.queue(),
                        MQConstants.MQOO_INPUT_AS_Q_DEF | MQConstants.MQOO_FAIL_IF_QUIESCING);
                output.println("Listening on " + config.queue());
                while (keepRunning.getAsBoolean()) {
                    String received = receive(manager, queue);
                    if (received != null) output.println("Received: " + received);
                }
            } catch (MQException ex) {
                if (keepRunning.getAsBoolean()) {
                    output.println("MQ connection failed (reason " + ex.reasonCode + "); reconnecting");
                }
            } finally {
                closeQuietly(queue, manager);
            }
            if (keepRunning.getAsBoolean()) {
                try {
                    Thread.sleep(config.reconnectMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 失効したキューハンドルと接続を閉じる。閉鎖失敗は再接続処理を妨げない。
     *
     * @param queue 開いたキュー。接続失敗時はnull
     * @param manager 開いた接続。接続失敗時はnull
     */
    private void closeQuietly(MQQueue queue, MQQueueManager manager) {
        try {
            if (queue != null) queue.close();
        } catch (MQException ignored) { }
        try {
            if (manager != null) manager.disconnect();
        } catch (MQException ignored) { }
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
            return receive(manager, queue);
        } finally {
            queue.close();
        }
    }

    /**
     * 既に開いたキューから一件受信し、本文を読んだ後に確定する。
     *
     * @param manager syncpointを確定する接続
     * @param queue 入力用に開いたキュー
     * @return UTF-8本文。待機時間内に到着しないときはnull
     * @throws MQException MQGETまたはcommit/backout失敗時
     * @throws IOException 本文の読取り失敗時
     */
    private String receive(MQQueueManager manager, MQQueue queue) throws MQException, IOException {
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
    }
}
