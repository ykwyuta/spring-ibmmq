package com.example;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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

    /** 無効コマンドと存在しないキューは異なる失敗として報告する。 */
    @Test
    void invalidCommandAndMissingQueueFailClearly() {
        assertThatThrownBy(() -> run(config(0), new String[] {"unknown"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("send [text]");
        NativeMqConfig invalidQueue = new NativeMqConfig("localhost", 1414, "DEV.APP.SVRCONN",
                "QM1", "app", System.getenv("IBMMQ_PASSWORD"), "MISSING.QUEUE", 0);
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
        NativeMqConfig loaded = config(0);
        assertThat(loaded.host()).isEqualTo("localhost");
        assertThat(loaded.queue()).isEqualTo(QUEUE);
        assertThat(loaded.connectionProperties().get(MQConstants.TRANSPORT_PROPERTY))
                .isEqualTo(MQConstants.TRANSPORT_MQSERIES_CLIENT);
    }

    /** 指定した待ち時間を持つ実MQの接続設定を作る。 */
    private NativeMqConfig config(int waitMillis) {
        Map<String, String> environment = new HashMap<>();
        environment.put("IBMMQ_PASSWORD", System.getenv("IBMMQ_PASSWORD"));
        environment.put("IBMMQ_WAIT_MILLIS", Integer.toString(waitMillis));
        return NativeMqConfig.fromEnvironment(environment);
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
