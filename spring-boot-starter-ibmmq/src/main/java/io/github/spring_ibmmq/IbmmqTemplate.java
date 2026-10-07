package io.github.spring_ibmmq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.constants.MQConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/** IBM MQ native API による送受信テンプレート。送信接続を再利用する。 */
public class IbmmqTemplate implements AutoCloseable {
    private final IbmmqConnectionFactory connectionFactory;
    private final IbmmqMessageConverter converter;
    private MQQueueManager senderManager;
    private boolean closed;

    /**
     * 送信に利用する接続ファクトリーを受け取る。
     *
     * @param connectionFactory 接続ファクトリー
     */
    public IbmmqTemplate(IbmmqConnectionFactory connectionFactory) {
        this(connectionFactory, new SimpleIbmmqMessageConverter());
    }

    /**
     * 接続ファクトリーとメッセージ変換器を受け取る。
     *
     * @param connectionFactory 接続ファクトリー
     * @param converter 変換に使用する bean
     */
    public IbmmqTemplate(IbmmqConnectionFactory connectionFactory, IbmmqMessageConverter converter) {
        this.connectionFactory = connectionFactory;
        this.converter = converter;
    }

    /**
     * UTF-8 文字列をキューへ送信する。
     *
     * @param queue 送信先キュー名
     * @param body 本文
     */
    public void send(String queue, String body) {
        send(queue, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * UTF-8 テキスト形式のバイト列としてキューへ送信する。
     * バイナリ形式を使う場合は native オプションを指定する overload を使う。
     *
     * @param queue 送信先キュー名
     * @param body 本文のバイト列
     */
    public void send(String queue, byte[] body) {
        send(queue, message -> {
            message.format = MQConstants.MQFMT_STRING;
            message.characterSet = 1208;
            try {
                message.write(body);
            } catch (IOException ex) {
                throw new IbmmqException("Unable to write MQ message", ex);
            }
        }, options -> {});
    }

    /**
     * 設定済みの変換器でペイロードを MQMessage に変換して送る。
     *
     * @param queue 送信先キュー名
     * @param payload 送信する値
     */
    public void convertAndSend(String queue, Object payload) {
        send(queue, message -> {
            try {
                converter.write(payload, message);
            } catch (IOException ex) {
                throw new IbmmqException("Unable to convert MQ message", ex);
            }
        }, options -> {});
    }

    /**
     * キューから1件受信する。タイムアウト時は {@code null} を返す。
     * 受信は MQGMO_NO_SYNCPOINT で行い、返す前にキューから取り除かれる。
     *
     * @param queue 受信元キュー名
     * @param timeoutMillis 待ち時間（ミリ秒）。0 は即時取得
     * @return 受信した native メッセージ、または {@code null}
     */
    public MQMessage receive(String queue, int timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("timeoutMillis must be non-negative");
        MQQueueManager manager = null;
        MQQueue source = null;
        try {
            manager = connectionFactory.createConnection();
            source = manager.accessQueue(queue, MQConstants.MQOO_INPUT_AS_Q_DEF | MQConstants.MQOO_FAIL_IF_QUIESCING);
            MQGetMessageOptions options = new MQGetMessageOptions();
            options.options = MQConstants.MQGMO_NO_SYNCPOINT
                    | (timeoutMillis == 0 ? MQConstants.MQGMO_NO_WAIT : MQConstants.MQGMO_WAIT);
            options.waitInterval = timeoutMillis;
            MQMessage message = new MQMessage();
            try {
                source.get(message, options);
                return message;
            } catch (MQException ex) {
                if (ex.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) return null;
                throw ex;
            }
        } catch (MQException ex) {
            throw new IbmmqException("MQGET failed for " + queue, ex);
        } finally {
            closeResources(source, manager);
        }
    }

    /**
     * キューから1件受信し、指定された型へ変換する。
     *
     * @param queue 受信元キュー名
     * @param timeoutMillis 待ち時間（ミリ秒）
     * @param targetType 変換後の型
     * @param <T> 変換後の型
     * @return 変換した値、またはタイムアウト時に {@code null}
     */
    public <T> T receiveAndConvert(String queue, int timeoutMillis, Class<T> targetType) {
        MQMessage message = receive(queue, timeoutMillis);
        if (message == null) return null;
        try {
            return targetType.cast(converter.read(message, targetType));
        } catch (IOException ex) {
            throw new IbmmqException("Unable to convert MQ message from " + queue, ex);
        }
    }

    /**
     * MQMD、本文、MQPMO を利用側で設定して送信する。
     * MQPMO_SYNCPOINT を指定した場合は送信後に commit する。
     * 送信接続は再利用し、MQ例外後に破棄して次回送信で作り直す。
     *
     * @param queue 送信先キュー名
     * @param messageCustomizer 本文と MQMD の設定
     * @param optionsCustomizer MQPMO の設定
     * @throws IbmmqException IBM MQ の接続や送信に失敗した場合
     */
    public void send(String queue, Consumer<MQMessage> messageCustomizer,
                     Consumer<MQPutMessageOptions> optionsCustomizer) {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(messageCustomizer, "messageCustomizer");
        Objects.requireNonNull(optionsCustomizer, "optionsCustomizer");
        MQMessage message = new MQMessage();
        MQPutMessageOptions options = new MQPutMessageOptions();
        messageCustomizer.accept(message);
        optionsCustomizer.accept(options);
        boolean syncpoint = (options.options & MQConstants.MQPMO_SYNCPOINT) != 0;
        synchronized (this) {
            if (closed) throw new IllegalStateException("IbmmqTemplate is closed");
            MQQueueManager manager = null;
            MQQueue destination = null;
            boolean success = false;
            try {
                manager = senderManager();
                destination = manager.accessQueue(queue, MQConstants.MQOO_OUTPUT | MQConstants.MQOO_FAIL_IF_QUIESCING);
                destination.put(message, options);
                if (syncpoint) manager.commit();
                success = true;
            } catch (MQException ex) {
                if (syncpoint && manager != null) backout(manager);
                throw new IbmmqException("MQPUT failed for " + queue, ex);
            } catch (RuntimeException ex) {
                if (syncpoint && manager != null) backout(manager);
                throw ex;
            } finally {
                closeQueue(destination);
                if (!success) disconnectSender();
            }
        }
    }

    /**
     * 初回送信時または接続失敗後に送信専用接続を作る。
     * 呼び出し元はこのテンプレートのモニターを保持する。
     *
     * @return 再利用する送信接続
     * @throws MQException 接続に失敗した場合
     */
    private MQQueueManager senderManager() throws MQException {
        if (senderManager == null) senderManager = connectionFactory.createConnection();
        return senderManager;
    }

    /** 送信接続を閉じ、次回送信で再作成できる状態にする。 */
    private void disconnectSender() {
        MQQueueManager manager = senderManager;
        senderManager = null;
        try {
            if (manager != null) manager.disconnect();
        } catch (MQException ignored) { }
    }

    /**
     * アプリケーション終了時に保持中の送信接続を閉じる。
     * 以後の送信は拒否する。
     */
    @Override
    public synchronized void close() {
        closed = true;
        disconnectSender();
    }

    /**
     * syncpoint 送信が失敗したとき、未確定の更新を取り消す。
     *
     * @param manager 取り消し対象の接続
     */
    private void backout(MQQueueManager manager) {
        try {
            manager.backout();
        } catch (MQException ignored) { }
    }

    /**
     * 一回の送受信で開いたキューハンドルを閉じる。
     *
     * @param queue 開いたキューハンドル
     */
    private void closeQueue(MQQueue queue) {
        try {
            if (queue != null) queue.close();
        } catch (MQException ignored) { }
    }

    /**
     * 同期受信で開いたキューと受信専用接続を閉じる。
     *
     * @param queue 開いたキューハンドル
     * @param manager 開いた接続
     */
    private void closeResources(MQQueue queue, MQQueueManager manager) {
        closeQueue(queue);
        try {
            if (manager != null) manager.disconnect();
        } catch (MQException ignored) { }
    }
}
