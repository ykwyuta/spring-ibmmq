package io.github.spring_ibmmq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/** アノテーション付きメソッドを検出し、ワーカーごとに独立した MQ 接続で受信する。 */
public class IbmmqListenerRegistry implements SmartInitializingSingleton, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(IbmmqListenerRegistry.class);
    private final ApplicationContext context;
    private final IbmmqConnectionFactory connectionFactory;
    private final IbmmqProperties settings;
    private final IbmmqMessageConverter defaultConverter;
    private final List<Endpoint> endpoints = new ArrayList<>();
    private volatile boolean running;
    private ExecutorService executor;

    /**
     * 受信メソッドの探索と接続に必要なオブジェクトを受け取る。
     *
     * @param context 受信メソッドを持つ bean の探索先
     * @param connectionFactory ワーカーの接続ファクトリー
     * @param settings 受信時の共通設定
     * @param defaultConverter 既定のメッセージ変換器
     */
    public IbmmqListenerRegistry(ApplicationContext context, IbmmqConnectionFactory connectionFactory,
                                 IbmmqProperties settings, IbmmqMessageConverter defaultConverter) {
        this.context = context;
        this.connectionFactory = connectionFactory;
        this.settings = settings;
        this.defaultConverter = defaultConverter;
    }

    /** 全 singleton の生成後に受信メソッドを探し、引数と設定を検証する。 */
    @Override
    public void afterSingletonsInstantiated() {
        for (String name : context.getBeanDefinitionNames()) {
            Object bean = context.getBean(name);
            Class<?> targetType = AopUtils.getTargetClass(bean);
            for (Method method : targetType.getMethods()) {
                IbmmqListener annotation = AnnotatedElementUtils.findMergedAnnotation(method, IbmmqListener.class);
                if (annotation == null) continue;
                if (annotation.value().isBlank() || annotation.concurrency() < 1) {
                    throw new IllegalArgumentException("Invalid @IbmmqListener on " + method);
                }
                IbmmqMessageConverter converter = annotation.messageConverter().isBlank()
                        ? defaultConverter : context.getBean(annotation.messageConverter(), IbmmqMessageConverter.class);
                if (method.getParameterCount() != 1 || !converter.supportsRead(method.getParameterTypes()[0])) {
                    throw new IllegalArgumentException("@IbmmqListener requires one argument supported by its converter: " + method);
                }
                Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
                ReflectionUtils.makeAccessible(invocable);
                IbmmqGetOptionsCustomizer customizer = annotation.getOptionsCustomizer().isBlank()
                        ? options -> {}
                        : context.getBean(annotation.getOptionsCustomizer(), IbmmqGetOptionsCustomizer.class);
                IbmmqListenerErrorHandler errorHandler = annotation.errorHandler().isBlank()
                        ? null : context.getBean(annotation.errorHandler(), IbmmqListenerErrorHandler.class);
                int wait = annotation.waitInterval() < 0 ? settings.getWaitInterval() : annotation.waitInterval();
                if (wait < 0) throw new IllegalArgumentException("waitInterval must be non-negative: " + method);
                endpoints.add(new Endpoint(bean, invocable, annotation.value(), annotation.concurrency(), wait,
                        customizer, converter, errorHandler));
            }
        }
    }

    /** 登録した受信先ごとに指定数のワーカーを起動する。 */
    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        int workers = endpoints.stream().mapToInt(Endpoint::concurrency).sum();
        if (workers == 0) return;
        // Platform threads keep non-web consumer applications alive.
        executor = Executors.newFixedThreadPool(workers, Thread.ofPlatform().name("ibmmq-listener-", 0).factory());
        for (Endpoint endpoint : endpoints) {
            for (int worker = 0; worker < endpoint.concurrency(); worker++) {
                executor.submit(() -> poll(endpoint));
            }
        }
    }

    /**
     * キューからメッセージを取得し、メソッドの結果に応じて commit または backout する。
     * 接続が失われた場合は新しい接続で受信を再開する。
     *
     * @param endpoint このワーカーが担当する受信先
     */
    private void poll(Endpoint endpoint) {
        while (running) {
            MQQueueManager manager = null;
            MQQueue queue = null;
            try {
                manager = connectionFactory.createConnection();
                queue = manager.accessQueue(endpoint.queue(), MQConstants.MQOO_INPUT_AS_Q_DEF | MQConstants.MQOO_FAIL_IF_QUIESCING);
                while (running) {
                    MQGetMessageOptions options = new MQGetMessageOptions();
                    options.options = MQConstants.MQGMO_WAIT | MQConstants.MQGMO_SYNCPOINT | MQConstants.MQGMO_FAIL_IF_QUIESCING;
                    options.waitInterval = endpoint.waitInterval();
                    endpoint.customizer().customize(options);
                    if ((options.options & MQConstants.MQGMO_SYNCPOINT) == 0 || options.waitInterval < 0) {
                        throw new IllegalArgumentException("Listener must use MQGMO_SYNCPOINT and finite waitInterval");
                    }
                    MQMessage message = new MQMessage();
                    try {
                        queue.get(message, options);
                    } catch (MQException ex) {
                        if (ex.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) continue;
                        throw ex;
                    }
                    if (!running) {
                        manager.backout();
                        break;
                    }
                    try {
                        invoke(endpoint, message);
                        manager.commit();
                    } catch (Exception ex) {
                        log.error("Listener failed for queue {}", endpoint.queue(), ex);
                        IbmmqFailureAction action;
                        try {
                            action = endpoint.errorHandler() == null ? IbmmqFailureAction.REQUEUE
                                    : endpoint.errorHandler().onError(message, ex);
                        } catch (RuntimeException handlerFailure) {
                            log.error("Listener error handler failed for queue {}", endpoint.queue(), handlerFailure);
                            action = IbmmqFailureAction.REQUEUE;
                        }
                        if (action == IbmmqFailureAction.DISCARD) {
                            manager.commit();
                        } else {
                            manager.backout();
                            pause(settings.getFailureDelay());
                        }
                    }
                }
            } catch (MQException ex) {
                if (running) log.warn("MQ listener connection failed for {}; retrying", endpoint.queue(), ex);
            } catch (RuntimeException ex) {
                if (running) log.error("MQ listener failed for {}; retrying", endpoint.queue(), ex);
            } finally {
                close(queue, manager);
            }
            if (running) pause(settings.getReconnectDelay());
        }
    }

    /**
     * 本文を宣言された引数型に変換して受信メソッドを呼び出す。
     *
     * @param endpoint 呼び出す bean とメソッド
     * @param message 受信した IBM MQ メッセージ
     * @throws IOException 本文の読み取りに失敗した場合
     * @throws InvocationTargetException 受信メソッドが例外を投げた場合
     * @throws IllegalAccessException 受信メソッドを呼び出せない場合
     */
    private void invoke(Endpoint endpoint, MQMessage message) throws IOException, InvocationTargetException, IllegalAccessException {
        Object argument = endpoint.converter().read(message, endpoint.method().getParameterTypes()[0]);
        endpoint.method().invoke(endpoint.bean(), argument);
    }

    /**
     * 再試行まで待機し、停止要求時は割り込み状態を維持する。
     *
     * @param millis 待機時間（ミリ秒）
     */
    private void pause(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ワーカーが保持するキューと接続を閉じる。
     *
     * @param queue 開いたキューハンドル
     * @param manager 開いた接続
     */
    private void close(MQQueue queue, MQQueueManager manager) {
        try { if (queue != null) queue.close(); } catch (MQException ignored) { }
        try { if (manager != null) manager.disconnect(); } catch (MQException ignored) { }
    }

    /** 受信を停止し、各ワーカーに割り込みを通知する。 */
    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 受信停止後に Spring の完了コールバックを呼び出す。
     *
     * @param callback 停止完了時のコールバック
     */
    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /** @return ワーカーの実行を要求している間は {@code true}。 */
    @Override
    public boolean isRunning() { return running; }

    /** @return Spring lifecycle におけるこの registry の起動・停止順序。 */
    @Override
    public int getPhase() { return Integer.MAX_VALUE - 100; }

    /** bean、メソッド、キュー名、受信オプションをまとめた受信先定義。 */
    private record Endpoint(Object bean, Method method, String queue, int concurrency, int waitInterval,
                            IbmmqGetOptionsCustomizer customizer, IbmmqMessageConverter converter,
                            IbmmqListenerErrorHandler errorHandler) { }
}
