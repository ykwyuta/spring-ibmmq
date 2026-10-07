package com.example;

import com.ibm.mq.constants.MQConstants;
import io.github.spring_ibmmq.IbmmqTemplate;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** IBM MQ への送信例を起動時に実行する demo アプリケーション。 */
@SpringBootApplication
public class DemoApplication {
    /** Spring Boot の設定クラスを生成する。 */
    public DemoApplication() {
    }

    /**
     * Spring Boot アプリケーションを起動する。
     *
     * @param args コマンドライン引数
     */
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    /**
     * 通常の文字列送信と MQMD/PMO を指定する送信を実行する。
     * {@code --receive-only} 指定時は送信を省き、listener のみ起動する。
     *
     * @param ibmmqTemplate 送信テンプレート
     * @return 起動後に送信する runner
     */
    @Bean
    ApplicationRunner sendExamples(IbmmqTemplate ibmmqTemplate) {
        return args -> {
            if (args.containsOption("receive-only")) return;
            ibmmqTemplate.send("DEV.QUEUE.1", "Hello from IbmmqTemplate");
            ibmmqTemplate.send("DEV.QUEUE.2", message -> {
                message.format = MQConstants.MQFMT_STRING;
                message.characterSet = 1208;
                message.persistence = MQConstants.MQPER_PERSISTENT;
                try {
                    message.write("Native MQMD and MQPMO example".getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }, options -> options.options |= MQConstants.MQPMO_FAIL_IF_QUIESCING);
        };
    }
}
