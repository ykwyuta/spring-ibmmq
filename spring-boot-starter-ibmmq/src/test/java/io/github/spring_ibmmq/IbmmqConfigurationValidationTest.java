package io.github.spring_ibmmq;

import com.ibm.mq.constants.MQConstants;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 接続プロパティと listener 宣言の境界値を検証する。 */
class IbmmqConfigurationValidationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IbmmqAutoConfiguration.class));

    @Test
    void optionalCredentialsAreOmittedWhenNullOrBlank() {
        checkCredentials(null, null, false, false);
        checkCredentials("", "", false, false);
        checkCredentials("app", "configured-value", true, true);
    }

    private void checkCredentials(String user, String password, boolean hasUser, boolean hasPassword) {
        IbmmqProperties properties = new IbmmqProperties();
        properties.setUser(user);
        properties.setPassword(password);
        AtomicReference<Hashtable<String, Object>> captured = new AtomicReference<>();
        IbmmqConnectionFactory factory = new IbmmqConnectionFactory(properties, List.of(values -> {
            captured.set(new Hashtable<>(values));
            throw new StopBeforeConnect();
        }));
        assertThatThrownBy(factory::createConnection).isInstanceOf(StopBeforeConnect.class);
        assertThat(captured.get().containsKey(MQConstants.USER_ID_PROPERTY)).isEqualTo(hasUser);
        assertThat(captured.get().containsKey(MQConstants.PASSWORD_PROPERTY)).isEqualTo(hasPassword);
    }

    @Test
    void rejectsBlankQueueName() {
        expectInvalid(BlankQueue.class, null);
    }

    @Test
    void rejectsZeroConcurrency() {
        expectInvalid(ZeroConcurrency.class, null);
    }

    @Test
    void rejectsNoArgument() {
        expectInvalid(NoArgument.class, null);
    }

    @Test
    void rejectsUnsupportedArgument() {
        expectInvalid(UnsupportedArgument.class, null);
    }

    @Test
    void rejectsNegativeWaitInterval() {
        expectInvalid(ValidListener.class, "ibmmq.wait-interval=-2");
    }

    private <T> void expectInvalid(Class<T> type, String property) {
        ApplicationContextRunner configured = runner.withBean(type);
        if (property != null) configured = configured.withPropertyValues(property);
        configured.run(context -> assertThat(context.getStartupFailure())
                .isInstanceOf(IllegalArgumentException.class));
    }

    static class BlankQueue {
        @IbmmqListener("") public void receive(String value) { }
    }

    static class ZeroConcurrency {
        @IbmmqListener(value = "DEV.QUEUE.1", concurrency = 0) public void receive(String value) { }
    }

    static class NoArgument {
        @IbmmqListener("DEV.QUEUE.1") public void receive() { }
    }

    static class UnsupportedArgument {
        @IbmmqListener("DEV.QUEUE.1") public void receive(int value) { }
    }

    static class ValidListener {
        @IbmmqListener("DEV.QUEUE.1") public void receive(String value) { }
    }

    private static final class StopBeforeConnect extends RuntimeException { }
}
