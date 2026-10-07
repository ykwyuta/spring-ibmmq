package io.github.spring_ibmmq;

import com.ibm.mq.MQMessage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** メッセージ変換は実際の MQMessage を使い、型ごとの結果を確認する。 */
class SimpleIbmmqMessageConverterTest {
    private final SimpleIbmmqMessageConverter converter = new SimpleIbmmqMessageConverter();

    @Test
    void convertsSupportedTypes() throws Exception {
        MQMessage text = new MQMessage();
        converter.write("hello", text);
        text.seek(0);
        assertThat(converter.read(text, String.class)).isEqualTo("hello");

        MQMessage bytes = new MQMessage();
        converter.write("bytes".getBytes(StandardCharsets.UTF_8), bytes);
        bytes.seek(0);
        assertThat(converter.read(bytes, byte[].class)).isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));

        MQMessage nativeMessage = new MQMessage();
        assertThat(converter.read(nativeMessage, MQMessage.class)).isSameAs(nativeMessage);
        assertThat(converter.supportsRead(String.class)).isTrue();
        assertThat(converter.supportsRead(byte[].class)).isTrue();
        assertThat(converter.supportsRead(MQMessage.class)).isTrue();
    }

    @Test
    void rejectsUnsupportedTypes() {
        assertThat(converter.supportsRead(Integer.class)).isFalse();
        assertThatThrownBy(() -> converter.write(42, new MQMessage()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.read(new MQMessage(), Integer.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
