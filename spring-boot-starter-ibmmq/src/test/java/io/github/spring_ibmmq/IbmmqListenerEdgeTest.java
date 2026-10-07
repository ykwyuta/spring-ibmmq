package io.github.spring_ibmmq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MQGET の境界条件と停止時の確定動作を検証する。Mock は MQ 例外注入に限定する。 */
class IbmmqListenerEdgeTest {
    private static final String QUEUE = "DEV.QUEUE.3";

    @Test
    void rejectsGetOptionsWithoutSyncpoint() throws Exception {
        verifyInvalidOptions(options -> options.options &= ~MQConstants.MQGMO_SYNCPOINT);
    }

    @Test
    void rejectsNegativeGetWait() throws Exception {
        verifyInvalidOptions(options -> options.waitInterval = -1);
    }

    @Test
    void nonTimeoutMqGetErrorsAreRetriedAndShutdownErrorsAreQuiet() throws Exception {
        IbmmqConnectionFactory factory = mock(IbmmqConnectionFactory.class);
        MQQueueManager manager = mock(MQQueueManager.class);
        MQQueue queue = mock(MQQueue.class);
        when(factory.createConnection()).thenReturn(manager);
        when(manager.accessQueue(anyString(), anyInt())).thenReturn(queue);
        doThrow(new MQException(MQConstants.MQCC_FAILED, MQConstants.MQRC_OPTIONS_ERROR, null))
                .when(queue).get(any(MQMessage.class), any(MQGetMessageOptions.class));

        AtomicReference<IbmmqListenerRegistry> registryRef = new AtomicReference<>();
        CountDownLatch attempted = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        IbmmqGetOptionsCustomizer customizer = options -> {
            if (calls.incrementAndGet() == 2) setRunning(registryRef.get(), false);
            attempted.countDown();
        };
        try (Fixture fixture = new Fixture(factory, customizer, registryRef)) {
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.registry.stop();
        }
    }

    @Test
    void messageFetchedDuringShutdownIsBackedOut() throws Exception {
        IbmmqConnectionFactory factory = realFactory();
        IbmmqTemplate template = new IbmmqTemplate(factory);
        while (template.receive(QUEUE, 0) != null) { }
        template.send(QUEUE, "keep on queue");

        AtomicReference<IbmmqListenerRegistry> registryRef = new AtomicReference<>();
        CountDownLatch polling = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(factory, options -> {
            setRunning(registryRef.get(), false);
            polling.countDown();
        }, registryRef)) {
            assertThat(polling.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitIdle(fixture.registry)).isTrue();
            fixture.registry.stop();
        }
        assertThat(template.receiveAndConvert(QUEUE, 1000, String.class)).isEqualTo("keep on queue");
    }

    @Test
    void cleanupAcceptsNoOpenHandles() throws Exception {
        AtomicReference<IbmmqListenerRegistry> registryRef = new AtomicReference<>();
        try (Fixture fixture = new Fixture(realFactory(), options -> {}, registryRef, false)) {
            Method close = IbmmqListenerRegistry.class.getDeclaredMethod("close", MQQueue.class, MQQueueManager.class);
            close.setAccessible(true);
            close.invoke(fixture.registry, null, null);
        }
    }

    /** 空キューでの MQRC_NO_MSG_AVAILABLE を通常のポーリングとして扱う。 */
    @Test
    void emptyQueueTimeoutContinuesPolling() throws Exception {
        IbmmqConnectionFactory factory = realFactory();
        IbmmqTemplate template = new IbmmqTemplate(factory);
        while (template.receive(QUEUE, 0) != null) { }
        CountDownLatch polls = new CountDownLatch(3);
        try (Fixture fixture = new Fixture(factory, options -> polls.countDown(), new AtomicReference<>())) {
            assertThat(polls.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void verifyInvalidOptions(IbmmqGetOptionsCustomizer change) throws Exception {
        AtomicReference<IbmmqListenerRegistry> registryRef = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch attempted = new CountDownLatch(2);
        try (Fixture fixture = new Fixture(realFactory(), options -> {
            change.customize(options);
            if (calls.incrementAndGet() == 2) setRunning(registryRef.get(), false);
            attempted.countDown();
        }, registryRef)) {
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.registry.stop();
        }
    }

    private static IbmmqConnectionFactory realFactory() {
        IbmmqProperties settings = new IbmmqProperties();
        settings.setPassword(System.getenv("IBMMQ_PASSWORD"));
        return new IbmmqConnectionFactory(settings, List.of());
    }

    private static void setRunning(IbmmqListenerRegistry registry, boolean value) {
        try {
            Field running = IbmmqListenerRegistry.class.getDeclaredField("running");
            running.setAccessible(true);
            running.setBoolean(registry, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean awaitIdle(IbmmqListenerRegistry registry) throws Exception {
        Field executorField = IbmmqListenerRegistry.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) executorField.get(registry);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (executor.getActiveCount() == 0) return true;
            Thread.sleep(20);
        }
        return false;
    }

    static class EdgeReceiver {
        @IbmmqListener(value = QUEUE, getOptionsCustomizer = "probe")
        public void receive(String body) { }
    }

    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final IbmmqListenerRegistry registry;

        Fixture(IbmmqConnectionFactory factory, IbmmqGetOptionsCustomizer customizer,
                AtomicReference<IbmmqListenerRegistry> registryRef) {
            this(factory, customizer, registryRef, true);
        }

        Fixture(IbmmqConnectionFactory factory, IbmmqGetOptionsCustomizer customizer,
                AtomicReference<IbmmqListenerRegistry> registryRef, boolean start) {
            context.registerBean(EdgeReceiver.class);
            context.registerBean("probe", IbmmqGetOptionsCustomizer.class, () -> customizer);
            context.refresh();
            IbmmqProperties properties = new IbmmqProperties();
            properties.setReconnectDelay(25);
            properties.setWaitInterval(50);
            registry = new IbmmqListenerRegistry(context, factory, properties, new SimpleIbmmqMessageConverter());
            registry.afterSingletonsInstantiated();
            registryRef.set(registry);
            if (start) registry.start();
        }

        @Override
        public void close() {
            registry.stop();
            context.close();
        }
    }
}
