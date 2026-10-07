package com.example;

import com.ibm.mq.MQMessage;
import com.ibm.mq.constants.MQConstants;
import io.github.spring_ibmmq.IbmmqGetOptionsCustomizer;
import io.github.spring_ibmmq.IbmmqListener;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** 通常本文と native MQMessage の両方を受信する demo listener。 */
@Component
public class DemoReceiver {
    private static final Logger log = LoggerFactory.getLogger(DemoReceiver.class);

    /** 受信用コンポーネントを生成する。 */
    public DemoReceiver() {
    }

    /**
     * UTF-8 の本文を文字列として受信する。
     *
     * @param body メッセージ本文
     */
    @IbmmqListener(value = "DEV.QUEUE.1", concurrency = 2)
    public void receiveText(String body) {
        log.info("Received text: {}", body);
    }

    /**
     * MQMD を参照し、MQMessage から本文を読み取る。
     *
     * @param message 受信した native メッセージ
     * @throws IOException 本文の読み取りに失敗した場合
     */
    @IbmmqListener(value = "DEV.QUEUE.2", getOptionsCustomizer = "demoGetOptions")
    public void receiveNative(MQMessage message) throws IOException {
        log.info("Received native message: persistence={}, body={}", message.persistence,
                message.readStringOfByteLength(message.getDataLength()));
    }

    /**
     * MQGET に文字コード変換オプションを追加するカスタマイザを登録する。
     *
     * @return demo 用の MQGET オプションカスタマイザ
     */
    @Bean
    IbmmqGetOptionsCustomizer demoGetOptions() {
        return options -> options.options |= MQConstants.MQGMO_CONVERT;
    }
}
