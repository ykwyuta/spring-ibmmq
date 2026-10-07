package io.github.spring_ibmmq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring AMQP の統合テストから採用したライフサイクル、並列受信、変換失敗、復旧の観点を実 MQ で検証する。 */
class IbmmqSpringAmqpReferenceIntegrationTest {
    private static final String QUEUE = "DEV.QUEUE.3";

    /** テスト間で共有する開発用キューを空にする。 */
    @BeforeEach
    void clearQueue() {
        IbmmqTemplate template = new IbmmqTemplate(realFactory());
        while (template.receive(QUEUE, 0) != null) { }
    }

    /** 停止中のメッセージを再開後に受信できることを確認する。 */
    @Test
    void stoppedListenerResumesWithoutLosingMessage() {
        LifecycleReceiver receiver = new LifecycleReceiver();
        runner().withBean(LifecycleReceiver.class, () -> receiver).run(context -> {
            IbmmqListenerRegistry registry = context.getBean(IbmmqListenerRegistry.class);
            IbmmqTemplate template = context.getBean(IbmmqTemplate.class);
            registry.stop();
            assertThat(registry.isRunning()).isFalse();

            template.send(QUEUE, "after restart");
            assertThat(receiver.received.await(300, TimeUnit.MILLISECONDS)).isFalse();
            registry.start();
            assertThat(registry.isRunning()).isTrue();
            assertThat(receiver.received.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(receiver.body.get()).isEqualTo("after restart");
            registry.stop();
            assertThat(template.receive(QUEUE, 0)).isNull();
        });
    }

    /** 三つのワーカーが同時に別のメッセージを処理し、重複しないことを確認する。 */
    @Test
    void concurrentListenersReceiveDistinctMessages() {
        ConcurrentReceiver receiver = new ConcurrentReceiver();
        runner().withBean(ConcurrentReceiver.class, () -> receiver).run(context -> {
            IbmmqTemplate template = context.getBean(IbmmqTemplate.class);
            try {
                for (int index = 0; index < 3; index++) template.send(QUEUE, "item-" + index);
                assertThat(receiver.entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(receiver.threads).hasSize(3);
            } finally {
                receiver.release.countDown();
            }
            assertThat(receiver.completed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(receiver.bodies).containsExactlyInAnyOrder("item-0", "item-1", "item-2");
            context.getBean(IbmmqListenerRegistry.class).stop();
            assertThat(template.receive(QUEUE, 0)).isNull();
        });
    }

    /** 変換時の例外がハンドラへ渡され、DISCARD で除去されることを確認する。 */
    @Test
    void conversionFailureReachesErrorHandlerAndDiscardsMessage() {
        ConversionReceiver receiver = new ConversionReceiver();
        IbmmqMessageConverter failingConverter = new IbmmqMessageConverter() {
            /** {@inheritDoc} */
            @Override
            public void write(Object payload, MQMessage message) throws IOException {
                new SimpleIbmmqMessageConverter().write(payload, message);
            }

            /** {@inheritDoc} */
            @Override
            public Object read(MQMessage message, Class<?> targetType) throws IOException {
                throw new IOException("conversion failed intentionally");
            }

            /** {@inheritDoc} */
            @Override
            public boolean supportsRead(Class<?> targetType) {
                return targetType == String.class;
            }
        };
        runner().withBean(ConversionReceiver.class, () -> receiver)
                .withBean("failingConverter", IbmmqMessageConverter.class, () -> failingConverter)
                .withBean("conversionErrorHandler", IbmmqListenerErrorHandler.class,
                        () -> (message, failure) -> {
                            receiver.failure.set(failure);
                            receiver.handled.countDown();
                            return IbmmqFailureAction.DISCARD;
                        })
                .run(context -> {
                    IbmmqTemplate template = context.getBean(IbmmqTemplate.class);
                    template.send(QUEUE, "unreadable");
                    assertThat(receiver.handled.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(receiver.failure.get()).isInstanceOf(IOException.class)
                            .hasMessage("conversion failed intentionally");
                    assertThat(receiver.calls).hasValue(0);
                    context.getBean(IbmmqListenerRegistry.class).stop();
                    assertThat(template.receive(QUEUE, 0)).isNull();
                });
    }

    /** 最初の接続例外後、実 MQ へ再接続して受信することを確認する。 */
    @Test
    void listenerRecoversAfterTransientConnectionException() {
        IbmmqTemplate sender = new IbmmqTemplate(realFactory());
        sender.send(QUEUE, "recovered");
        AtomicInteger attempts = new AtomicInteger();
        IbmmqConnectionFactory failingOnce = new IbmmqConnectionFactory(properties(), List.of()) {
            /** 最初の接続試行だけ例外を注入する。 */
            @Override
            public MQQueueManager createConnection() throws MQException {
                if (attempts.incrementAndGet() == 1) {
                    throw new MQException(MQConstants.MQCC_FAILED, MQConstants.MQRC_CONNECTION_BROKEN, null);
                }
                return super.createConnection();
            }
        };
        LifecycleReceiver receiver = new LifecycleReceiver();
        runner().withBean(IbmmqConnectionFactory.class, () -> failingOnce)
                .withBean(LifecycleReceiver.class, () -> receiver)
                .run(context -> {
                    assertThat(receiver.received.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(receiver.body.get()).isEqualTo("recovered");
                    assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
                    context.getBean(IbmmqListenerRegistry.class).stop();
                    assertThat(sender.receive(QUEUE, 0)).isNull();
                });
    }

    /** 実 MQ 用の共通 Spring 設定を作る。 */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IbmmqAutoConfiguration.class))
                .withPropertyValues("ibmmq.password=" + System.getenv("IBMMQ_PASSWORD"), "ibmmq.reconnect-delay=25",
                        "ibmmq.wait-interval=50");
    }

    /** 開発用キューマネージャーの接続ファクトリーを作る。 */
    private IbmmqConnectionFactory realFactory() {
        return new IbmmqConnectionFactory(properties(), List.of());
    }

    /** 開発用キューマネージャーの接続設定を作る。 */
    private IbmmqProperties properties() {
        IbmmqProperties settings = new IbmmqProperties();
        settings.setPassword(System.getenv("IBMMQ_PASSWORD"));
        return settings;
    }

    /** 停止・再開および接続復旧の結果を保持する。 */
    static class LifecycleReceiver {
        final CountDownLatch received = new CountDownLatch(1);
        final AtomicReference<String> body = new AtomicReference<>();

        /** 受け取った本文を記録する。 */
        @IbmmqListener(QUEUE)
        public void receive(String value) {
            body.set(value);
            received.countDown();
        }
    }

    /** 複数ワーカーが処理した本文とスレッドを保持する。 */
    static class ConcurrentReceiver {
        final CountDownLatch entered = new CountDownLatch(3);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(3);
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final Set<String> bodies = ConcurrentHashMap.newKeySet();

        /** ワーカーを同期させ、各スレッドの受信を記録する。 */
        @IbmmqListener(value = QUEUE, concurrency = 3)
        public void receive(String body) throws InterruptedException {
            threads.add(Thread.currentThread().getName());
            bodies.add(body);
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            completed.countDown();
        }
    }

    /** 変換失敗時にlistenerが呼ばれなかったことを記録する。 */
    static class ConversionReceiver {
        final CountDownLatch handled = new CountDownLatch(1);
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        /** 変換が成功した場合だけ呼ばれる受信メソッド。 */
        @IbmmqListener(value = QUEUE, messageConverter = "failingConverter",
                errorHandler = "conversionErrorHandler")
        public void receive(String body) {
            calls.incrementAndGet();
        }
    }
}
