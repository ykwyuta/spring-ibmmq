package io.github.spring_ibmmq;

import com.ibm.mq.MQMessage;
import com.ibm.mq.constants.MQConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** UTF-8 文字列、バイト列、native MQMessage を扱う既定の変換器。 */
public class SimpleIbmmqMessageConverter implements IbmmqMessageConverter {
    /** 既定の文字列・バイト列変換器を生成する。 */
    public SimpleIbmmqMessageConverter() {
    }

    /** {@inheritDoc} */
    @Override
    public void write(Object payload, MQMessage message) throws IOException {
        byte[] body;
        if (payload instanceof String text) {
            body = text.getBytes(StandardCharsets.UTF_8);
        } else if (payload instanceof byte[] bytes) {
            body = bytes;
        } else {
            throw new IllegalArgumentException("Unsupported payload type: " + payload.getClass().getName());
        }
        message.format = MQConstants.MQFMT_STRING;
        message.characterSet = 1208;
        message.write(body);
    }

    /** {@inheritDoc} */
    @Override
    public Object read(MQMessage message, Class<?> targetType) throws IOException {
        if (targetType == MQMessage.class) return message;
        if (!supportsRead(targetType)) {
            throw new IllegalArgumentException("Unsupported target type: " + targetType.getName());
        }
        byte[] body = new byte[message.getDataLength()];
        message.readFully(body);
        return targetType == String.class ? new String(body, StandardCharsets.UTF_8) : body;
    }

    /** {@inheritDoc} */
    @Override
    public boolean supportsRead(Class<?> targetType) {
        return targetType == String.class || targetType == byte[].class || targetType == MQMessage.class;
    }
}
