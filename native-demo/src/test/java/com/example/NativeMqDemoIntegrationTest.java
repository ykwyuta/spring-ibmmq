package com.example;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** IBM MQの実キューを使い、素のJava APIだけによる送受信を検証する。 */
class NativeMqDemoIntegrationTest {
    private static final String QUEUE = "DEV.QUEUE.3";

    /** テストで共用する開発用キューを空にする。 */
    @BeforeEach
    void clearQueue() throws Exception {
        MQQueueManager manager = new MQQueueManager("QM1", config(0).connectionProperties());
        try {
            MQQueue queue = manager.accessQueue(QUEUE, MQConstants.MQOO_INPUT_AS_Q_DEF);
            try {
                MQGetMessageOptions options = new MQGetMessageOptions();
                options.options = MQConstants.MQGMO_NO_WAIT | MQConstants.MQGMO_NO_SYNCPOINT;
                while (true) {
                    try {
                        queue.get(new MQMessage(), options);
                    } catch (MQException ex) {
                        if (ex.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) break;
                        throw ex;
                    }
                }
            } finally {
                queue.close();
            }
        } finally {
            manager.disconnect();
        }
    }

    /** 引数なしのコマンドがUTF-8本文を送信してから受信する。 */
    @Test
    void defaultCommandRoundTrips() throws Exception {
        String output = run(config(0), new String[0]);
        assertThat(output).contains("Sent: Hello from native IBM MQ")
                .contains("Received: Hello from native IBM MQ");
        assertThat(run(config(0), new String[] {"receive"})).contains("No message received");
    }

    /** 送信専用コマンドのMQMDと、受信専用コマンドの本文を確認する。 */
    @Test
    void sendAndReceiveCommandsUseNativeMqmdAndGmo() throws Exception {
        String body = "IBM MQ native 日本語";
        assertThat(run(config(0), new String[] {"send", body})).contains("Sent: " + body);

        MQQueueManager manager = new MQQueueManager("QM1", config(0).connectionProperties());
        try {
            MQQueue queue = manager.accessQueue(QUEUE, MQConstants.MQOO_INPUT_AS_Q_DEF);
            try {
                MQMessage message = new MQMessage();
                MQGetMessageOptions options = new MQGetMessageOptions();
                options.options = MQConstants.MQGMO_NO_WAIT | MQConstants.MQGMO_NO_SYNCPOINT;
                queue.get(message, options);
                assertThat(message.persistence).isEqualTo(MQConstants.MQPER_PERSISTENT);
                assertThat(message.format).isEqualTo(MQConstants.MQFMT_STRING);
                assertThat(message.characterSet).isEqualTo(1208);
                byte[] bytes = new byte[message.getDataLength()];
                message.readFully(bytes);
                assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(body);
            } finally {
                queue.close();
            }
        } finally {
            manager.disconnect();
        }

        run(config(0), new String[] {"send", body});
        assertThat(run(config(1000), new String[] {"receive"}))
                .contains("Received: " + body);
        run(config(0), new String[] {"send"});
        assertThat(run(config(1000), new String[] {"receive"}))
                .contains("Received: Hello from native IBM MQ");
    }

    /** 指定本文の往復と、空キューでの待機タイムアウトを確認する。 */
    @Test
    void explicitRoundtripAndWaitTimeout() throws Exception {
        assertThat(run(config(1000), new String[] {"roundtrip", "custom text"}))
                .contains("Sent: custom text", "Received: custom text");
        assertThat(run(config(20), new String[] {"receive"})).contains("No message received");
    }

    /** 対話モードの複数送受信が実MQの一つの接続を共有する。 */
    @Test
    void sessionReusesOneConnectionForMultipleCommands() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        NativeMqDemo demo = new NativeMqDemo(config(0), () -> {
            connections.incrementAndGet();
            return new MQQueueManager("QM1", config(0).connectionProperties());
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            demo.execute(new String[] {"session"},
                    new StringReader("send one\nsend 日本語\nreceive\nreceive\nquit\n"), output);
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Sent: one", "Sent: 日本語", "Received: one", "Received: 日本語");
        assertThat(connections).hasValue(1);
        assertThat(run(config(0), new String[] {"receive"})).contains("No message received");
    }

    /** 既存の実接続を切断した場合は失敗を通知し、次の入力で接続し直す。 */
    @Test
    void sessionReopensBrokenConnectionWithoutRepeatingFailedSend() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        AtomicReference<MQQueueManager> active = new AtomicReference<>();
        NativeMqDemo demo = new NativeMqDemo(config(0), () -> {
            MQQueueManager manager = new MQQueueManager("QM1", config(0).connectionProperties());
            active.set(manager);
            connections.incrementAndGet();
            return manager;
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8) {
            /** 最初の送信後に実接続を切断し、次の入力での回復を試す。 */
            @Override
            public void println(String text) {
                super.println(text);
                if (text.equals("Sent: before break")) {
                    try {
                        active.get().disconnect();
                    } catch (MQException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
        }) {
            demo.execute(new String[] {"session"}, new StringReader(
                    "send before break\nsend uncertain\nsend after break\nreceive\nreceive\nquit\n"), output);
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Sent: before break", "reconnecting on next command",
                "Sent: after break", "Received: before break", "Received: after break");
        assertThat(output).doesNotContain("Received: uncertain");
        assertThat(connections).hasValue(2);
        assertThat(run(config(0), new String[] {"receive"})).contains("No message received");
    }

    /** 無効入力を案内し、空キューの受信と入力終端を正常に扱う。 */
    @Test
    void sessionHandlesInvalidCommandAndEndOfInput() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new NativeMqDemo(config(0)).execute(new String[] {"session"},
                    new StringReader("unknown\nreceive\n"), output);
        }
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .contains("Use send <text>, receive, or quit", "No message received");
    }

    /** 無効コマンドと存在しないキューは異なる失敗として報告する。 */
    @Test
    void invalidCommandAndMissingQueueFailClearly() {
        assertThatThrownBy(() -> run(config(0), new String[] {"unknown"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("send [text]");
        NativeMqConfig invalidQueue = new NativeMqConfig("localhost", 1414, "DEV.APP.SVRCONN",
                "QM1", "app", System.getenv("IBMMQ_PASSWORD"), "MISSING.QUEUE", 0, 20);
        assertThatThrownBy(() -> run(invalidQueue, new String[] {"send"}))
                .isInstanceOf(MQException.class);
    }

    /** 想定外のMQGET例外をタイムアウトとして隠さず上位へ伝える。 */
    @Test
    void unexpectedGetFailurePropagates() throws Exception {
        MQQueueManager manager = mock(MQQueueManager.class);
        MQQueue queue = mock(MQQueue.class);
        when(manager.accessQueue(anyString(), anyInt())).thenReturn(queue);
        doThrow(new MQException(MQConstants.MQCC_FAILED, MQConstants.MQRC_OPTIONS_ERROR, null))
                .when(queue).get(any(MQMessage.class), any(MQGetMessageOptions.class));
        Method receive = NativeMqDemo.class.getDeclaredMethod("receive", MQQueueManager.class);
        receive.setAccessible(true);
        assertThatThrownBy(() -> receive.invoke(new NativeMqDemo(config(0)), manager))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(MQException.class);
    }

    /** 実行に必要なパスワードと受信待ち時間を検証する。 */
    @Test
    void configurationValidatesPasswordAndWait() {
        assertThatThrownBy(() -> NativeMqConfig.fromEnvironment(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IBMMQ_PASSWORD");
        assertThatThrownBy(() -> NativeMqConfig.fromEnvironment(Map.of("IBMMQ_PASSWORD", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeMqConfig.fromEnvironment(Map.of(
                "IBMMQ_PASSWORD", "configured-value", "IBMMQ_WAIT_MILLIS", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IBMMQ_WAIT_MILLIS");
        assertThatThrownBy(() -> NativeMqConfig.fromEnvironment(Map.of(
                "IBMMQ_PASSWORD", "configured-value", "IBMMQ_RECONNECT_MILLIS", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IBMMQ_RECONNECT_MILLIS");
        NativeMqConfig loaded = config(0);
        assertThat(loaded.host()).isEqualTo("localhost");
        assertThat(loaded.queue()).isEqualTo(QUEUE);
        assertThat(loaded.connectionProperties().get(MQConstants.TRANSPORT_PROPERTY))
                .isEqualTo(MQConstants.TRANSPORT_MQSERIES_CLIENT);
        assertThat(loaded.reconnectMillis()).isEqualTo(20);
    }

    /** 常駐受信が複数メッセージを取り、停止要求で終了する。 */
    @Test
    void listenPollsUntilStopped() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        NativeMqDemo demo = new NativeMqDemo(config(20));
        Thread listener;
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            listener = Thread.ofPlatform().start(() -> {
                try {
                    demo.execute(new String[] {"listen"}, output);
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            });
            try {
                awaitOutput(bytes, "Listening on " + QUEUE);
                run(config(0), new String[] {"send", "first"});
                run(config(0), new String[] {"send", "second"});
                awaitOutput(bytes, "Received: second");
                assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("Received: first");
            } finally {
                demo.stopListening();
                listener.join(2000);
                if (listener.isAlive()) {
                    listener.interrupt();
                    listener.join(3000);
                }
            }
        }
        assertThat(listener.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    /** 生きたMQ接続を切断しても、新しい接続とキューハンドルで受信を再開する。 */
    @Test
    void listenReconnectsAfterConnectionBreak() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<MQQueueManager> activeConnection = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean running = new AtomicBoolean(true);
        NativeMqDemo demo = new NativeMqDemo(config(20), () -> {
            MQQueueManager manager = new MQQueueManager("QM1", config(20).connectionProperties());
            activeConnection.set(manager);
            attempts.incrementAndGet();
            return manager;
        });
        Thread listener;
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            listener = Thread.ofPlatform().start(() -> {
                try {
                    demo.listen(output, running::get);
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            });
            try {
                awaitOutput(bytes, "Listening on " + QUEUE);
                activeConnection.get().disconnect();
                awaitOutput(bytes, "reconnecting");
                awaitAttempts(attempts, 2);
                run(config(0), new String[] {"send", "after reconnect"});
                awaitOutput(bytes, "Received: after reconnect");
            } finally {
                running.set(false);
                listener.interrupt();
                listener.join(5000);
            }
        }
        assertThat(listener.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    /** 初回の接続失敗後に待機してから再接続し、同じキューを受信する。 */
    @Test
    void listenRetriesInitialConnectionFailure() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean running = new AtomicBoolean(true);
        NativeMqDemo demo = new NativeMqDemo(config(20), () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new MQException(MQConstants.MQCC_FAILED, MQConstants.MQRC_Q_MGR_NOT_AVAILABLE, null);
            }
            return new MQQueueManager("QM1", config(20).connectionProperties());
        });
        Thread listener;
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            listener = Thread.ofPlatform().start(() -> {
                try {
                    demo.listen(output, running::get);
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            });
            try {
                awaitOutput(bytes, "reconnecting");
                awaitOutput(bytes, "Listening on " + QUEUE);
                run(config(0), new String[] {"send", "after initial failure"});
                awaitOutput(bytes, "Received: after initial failure");
            } finally {
                running.set(false);
                listener.interrupt();
                listener.join(5000);
            }
        }
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
        assertThat(listener.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    /** 常駐受信は即時取得による空回りを拒否し、キューのopen失敗後も停止できる。 */
    @Test
    void listenValidatesWaitAndStopsDuringReconnectDelay() throws Exception {
        assertThatThrownBy(() -> new NativeMqDemo(config(0)).listen(System.out, () -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        NativeMqConfig invalidQueue = new NativeMqConfig("localhost", 1414, "DEV.APP.SVRCONN",
                "QM1", "app", System.getenv("IBMMQ_PASSWORD"), "MISSING.QUEUE", 20, 5000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread listener;
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            listener = Thread.ofPlatform().start(() -> {
                try {
                    new NativeMqDemo(invalidQueue).listen(output, running::get);
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            });
            try {
                awaitOutput(bytes, "reconnecting");
                awaitThreadState(listener, Thread.State.TIMED_WAITING);
            } finally {
                running.set(false);
                listener.interrupt();
                listener.join(5000);
            }
        }
        assertThat(listener.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    /** 指定した待ち時間を持つ実MQの接続設定を作る。 */
    private NativeMqConfig config(int waitMillis) {
        Map<String, String> environment = new HashMap<>();
        environment.put("IBMMQ_PASSWORD", System.getenv("IBMMQ_PASSWORD"));
        environment.put("IBMMQ_WAIT_MILLIS", Integer.toString(waitMillis));
        environment.put("IBMMQ_RECONNECT_MILLIS", "20");
        return NativeMqConfig.fromEnvironment(environment);
    }

    /** 非同期listenerが指定したログを出すまで、期限付きで待つ。 */
    private void awaitOutput(ByteArrayOutputStream bytes, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!bytes.toString(StandardCharsets.UTF_8).contains(expected)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for " + expected + ": " + bytes.toString(StandardCharsets.UTF_8));
            }
            Thread.sleep(10);
        }
    }

    /** 接続生成回数が指定値に達するまで期限付きで待つ。 */
    private void awaitAttempts(AtomicInteger attempts, int count) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (attempts.get() < count) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Reconnect did not happen");
            Thread.sleep(10);
        }
    }

    /** 再接続間隔の待機に入るまで期限付きで待つ。 */
    private void awaitThreadState(Thread thread, Thread.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (thread.getState() != expected) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Thread did not enter " + expected);
            Thread.sleep(10);
        }
    }

    /** 標準出力を捕捉してコマンドを実行する。 */
    private String run(NativeMqConfig config, String[] args) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new NativeMqDemo(config).execute(args, output);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
