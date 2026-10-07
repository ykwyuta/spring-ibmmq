package io.github.spring_ibmmq;

import com.ibm.mq.MQGetMessageOptions;

/** listener の MQGET ごとに native オプションを調整する拡張点。 */
@FunctionalInterface
public interface IbmmqGetOptionsCustomizer {
    /**
     * MQGET オプションを変更する。MQGMO_SYNCPOINT は維持する。
     *
     * @param options 既定値を設定済みの可変の MQGET オプション
     */
    void customize(MQGetMessageOptions options);
}
