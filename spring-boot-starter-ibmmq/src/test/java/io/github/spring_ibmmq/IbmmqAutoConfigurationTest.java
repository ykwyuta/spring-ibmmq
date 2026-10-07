package io.github.spring_ibmmq;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class IbmmqAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IbmmqAutoConfiguration.class));

    @Test
    void createsTemplateWithoutConnectingToMq() {
        runner.withPropertyValues("ibmmq.host=mq.example", "ibmmq.port=1515")
                .run(context -> {
                    assertThat(context).hasSingleBean(IbmmqTemplate.class);
                    assertThat(context).hasSingleBean(IbmmqConnectionFactory.class);
                    assertThat(context.getBean(IbmmqProperties.class).getHost()).isEqualTo("mq.example");
                    assertThat(context.getBean(IbmmqProperties.class).getPort()).isEqualTo(1515);
                    context.getBean(IbmmqListenerRegistry.class).start();
                });
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("ibmmq.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IbmmqTemplate.class));
    }
}
