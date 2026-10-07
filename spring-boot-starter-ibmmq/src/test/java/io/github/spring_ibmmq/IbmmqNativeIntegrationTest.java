package io.github.spring_ibmmq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通常経路は Docker Compose の実 IBM MQ に対して検証する。 */
class IbmmqNativeIntegrationTest {
    private static final String TEXT_QUEUE = "DEV.QUEUE.1";
    private static final String NATIVE_QUEUE = "DEV.QUEUE.2";
    private static final String DIRECT_QUEUE = "DEV.QUEUE.3";

    private final IbmmqProperties properties = new IbmmqProperties();

    @BeforeEach
    void configure() throws MQException {
        properties.setPassword(System.getenv("IBMMQ_PASSWORD"));
        drain(TEXT_QUEUE);
        drain(NATIVE_QUEUE);
        drain(DIRECT_QUEUE);
    }

    @Test
    void sendsTextBytesAndCustomizedMessageToRealQueue() throws Exception {
        AtomicInteger customized = new AtomicInteger();
        IbmmqConnectionFactory factory = new IbmmqConnectionFactory(properties, List.of(p -> {
            p.put(MQConstants.HOST_NAME_PROPERTY, "localhost");
            customized.incrementAndGet();
        }));
        IbmmqTemplate template = new IbmmqTemplate(factory);

        template.send(DIRECT_QUEUE, "first");
        template.send(DIRECT_QUEUE, "second".getBytes(StandardCharsets.UTF_8));
        template.send(DIRECT_QUEUE, message -> {
            message.format = MQConstants.MQFMT_STRING;
            message.characterSet = 1208;
            message.persistence = MQConstants.MQPER_PERSISTENT;
            try {
                message.write("third".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }, options -> options.options |= MQConstants.MQPMO_SYNCPOINT);

        assertThat(List.of(read(DIRECT_QUEUE), read(DIRECT_QUEUE), read(DIRECT_QUEUE)))
                .containsExactlyInAnyOrder("first", "second", "third");
        assertThat(customized).hasValue(3);
    }

    @Test
    void invalidQueueReportsNativeFailure() {
        IbmmqTemplate template = new IbmmqTemplate(factory());
        assertThatThrownBy(() -> template.send("MISSING.QUEUE", "test"))
                .isInstanceOf(IbmmqException.class)
                .hasCauseInstanceOf(MQException.class);
    }

    @Test
    void templateConvertsAndSynchronouslyReceives() {
        IbmmqTemplate template = new IbmmqTemplate(factory());
        assertThat(template.receive(DIRECT_QUEUE, 0)).isNull();
        assertThat(template.receiveAndConvert(DIRECT_QUEUE, 20, String.class)).isNull();
        assertThatThrownBy(() -> template.receive(DIRECT_QUEUE, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.receive("MISSING.QUEUE", 0))
                .isInstanceOf(IbmmqException.class);

        template.convertAndSend(DIRECT_QUEUE, "converted");
        assertThat(template.receiveAndConvert(DIRECT_QUEUE, 1000, String.class)).isEqualTo("converted");
        template.convertAndSend(DIRECT_QUEUE, "native".getBytes(StandardCharsets.UTF_8));
        assertThat(template.receive(DIRECT_QUEUE, 0)).isNotNull();
    }

    @Test
    void annotatedListenersReceiveAllSupportedArgumentTypesAndRetryFailure() {
        Receiver receiver = new Receiver();
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IbmmqAutoConfiguration.class))
                .withPropertyValues("ibmmq.password=" + System.getenv("IBMMQ_PASSWORD"), "ibmmq.failure-delay=50")
                .withBean(Receiver.class, () -> receiver)
                .withBean("convert", IbmmqGetOptionsCustomizer.class,
                        () -> options -> options.options |= MQConstants.MQGMO_CONVERT);

        runner.run(context -> {
            IbmmqTemplate template = context.getBean(IbmmqTemplate.class);
            template.send(TEXT_QUEUE, "retry me");
            template.send(NATIVE_QUEUE, "native");
            template.send(DIRECT_QUEUE, "bytes".getBytes(StandardCharsets.UTF_8));
            assertThat(receiver.received.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(receiver.attempts).hasValue(2);
            assertThat(receiver.text.get()).isEqualTo("retry me");
            assertThat(receiver.nativeBody.get()).isEqualTo("native");
            assertThat(receiver.bytes.get()).isEqualTo("bytes");
        });
    }

    @Test
    void namedConverterAndErrorHandlerCanDiscardFailedMessage() throws Exception {
        DiscardReceiver receiver = new DiscardReceiver();
        IbmmqMessageConverter customConverter = new IbmmqMessageConverter() {
            private final SimpleIbmmqMessageConverter delegate = new SimpleIbmmqMessageConverter();

            @Override
            public void write(Object payload, MQMessage message) throws IOException {
                delegate.write(((CustomPayload) payload).value(), message);
            }

            @Override
            public Object read(MQMessage message, Class<?> targetType) throws IOException {
                return new CustomPayload((String) delegate.read(message, String.class));
            }

            @Override
            public boolean supportsRead(Class<?> targetType) {
                return targetType == CustomPayload.class;
            }
        };
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IbmmqAutoConfiguration.class))
                .withPropertyValues("ibmmq.password=" + System.getenv("IBMMQ_PASSWORD"))
                .withBean(DiscardReceiver.class, () -> receiver)
                .withBean("customConverter", IbmmqMessageConverter.class, () -> customConverter)
                .withBean("discardHandler", IbmmqListenerErrorHandler.class, () -> (message, failure) -> {
                    receiver.handled.countDown();
                    return IbmmqFailureAction.DISCARD;
                });

        runner.run(context -> {
            context.getBean(IbmmqTemplate.class).convertAndSend(DIRECT_QUEUE, new CustomPayload("discard me"));
            assertThat(receiver.handled.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(receiver.received.get()).isEqualTo("discard me");
            assertThat(context.getBean(IbmmqTemplate.class).receive(DIRECT_QUEUE, 0)).isNull();
        });
    }

    private IbmmqConnectionFactory factory() {
        return new IbmmqConnectionFactory(properties, List.of());
    }

    private String read(String queueName) throws Exception {
        try (NativeQueue queue = new NativeQueue(factory(), queueName)) {
            MQMessage message = new MQMessage();
            MQGetMessageOptions options = new MQGetMessageOptions();
            options.options = MQConstants.MQGMO_WAIT;
            options.waitInterval = 5000;
            queue.queue.get(message, options);
            byte[] data = new byte[message.getDataLength()];
            message.readFully(data);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private void drain(String queueName) throws MQException {
        try (NativeQueue queue = new NativeQueue(factory(), queueName)) {
            while (true) {
                try {
                    MQGetMessageOptions options = new MQGetMessageOptions();
                    options.options = MQConstants.MQGMO_NO_WAIT;
                    queue.queue.get(new MQMessage(), options);
                } catch (MQException ex) {
                    if (ex.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) return;
                    throw ex;
                }
            }
        }
    }

    static class Receiver {
        final CountDownLatch received = new CountDownLatch(3);
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<String> text = new AtomicReference<>();
        final AtomicReference<String> nativeBody = new AtomicReference<>();
        final AtomicReference<String> bytes = new AtomicReference<>();

        @IbmmqListener(value = TEXT_QUEUE, concurrency = 2)
        public void text(String body) {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("once");
            text.set(body);
            received.countDown();
        }

        @IbmmqListener(value = NATIVE_QUEUE, getOptionsCustomizer = "convert", waitInterval = 250)
        public void nativeMessage(MQMessage message) throws IOException {
            nativeBody.set(message.readStringOfByteLength(message.getDataLength()));
            received.countDown();
        }

        @IbmmqListener(DIRECT_QUEUE)
        public void bytes(byte[] body) {
            bytes.set(new String(body, StandardCharsets.UTF_8));
            received.countDown();
        }
    }

    private record CustomPayload(String value) { }

    static class DiscardReceiver {
        final CountDownLatch handled = new CountDownLatch(1);
        final AtomicReference<String> received = new AtomicReference<>();

        @IbmmqListener(value = DIRECT_QUEUE, messageConverter = "customConverter", errorHandler = "discardHandler")
        public void receive(CustomPayload payload) {
            received.set(payload.value());
            throw new IllegalStateException("discard intentionally");
        }
    }

    private static class NativeQueue implements AutoCloseable {
        final MQQueueManager manager;
        final MQQueue queue;

        NativeQueue(IbmmqConnectionFactory factory, String name) throws MQException {
            manager = factory.createConnection();
            queue = manager.accessQueue(name, MQConstants.MQOO_INPUT_AS_Q_DEF);
        }

        @Override
        public void close() throws MQException {
            queue.close();
            manager.disconnect();
        }
    }
}
