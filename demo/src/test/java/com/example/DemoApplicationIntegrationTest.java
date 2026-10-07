package com.example;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** demo の通常の送受信を Docker Compose の実 IBM MQ で確認する。 */
@ExtendWith(OutputCaptureExtension.class)
class DemoApplicationIntegrationTest {
    @Test
    void startupSendsAndBothListenersReceive(CapturedOutput output) throws InterruptedException {
        try (ConfigurableApplicationContext context = SpringApplication.run(DemoApplication.class)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline &&
                    (!output.getOut().contains("Received text: Hello from IbmmqTemplate") ||
                     !output.getOut().contains("Received native message: persistence=1, body=Native MQMD and MQPMO example"))) {
                Thread.sleep(50);
            }
            assertThat(output.getOut()).contains("Received text: Hello from IbmmqTemplate");
            assertThat(output.getOut()).contains("Received native message: persistence=1, body=Native MQMD and MQPMO example");
        }
    }

    /** 受信専用モードでも listener が登録されることを確認する。 */
    @Test
    void receiveOnlyStartsWithoutSending() {
        try (ConfigurableApplicationContext context = SpringApplication.run(DemoApplication.class, "--receive-only")) {
            assertThat(context.getBean(DemoReceiver.class)).isNotNull();
        }
    }
}
