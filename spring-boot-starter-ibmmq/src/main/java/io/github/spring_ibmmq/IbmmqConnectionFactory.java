package io.github.spring_ibmmq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import java.util.Hashtable;
import java.util.List;

/** IBM MQ の native 接続を作成する。listener ワーカーは接続を共有しない。 */
public class IbmmqConnectionFactory {
    private final IbmmqProperties settings;
    private final List<IbmmqConnectionPropertiesCustomizer> customizers;

    /**
     * 接続設定と追加の接続プロパティカスタマイザを受け取る。
     *
     * @param settings 基本の接続設定
     * @param customizers 接続ごとに適用するカスタマイザ
     */
    public IbmmqConnectionFactory(IbmmqProperties settings, List<IbmmqConnectionPropertiesCustomizer> customizers) {
        this.settings = settings;
        this.customizers = customizers;
    }

    /**
     * IBM MQ の接続プロパティを組み立て、新しい接続を作る。
     * 呼び出し側は返された接続を {@code disconnect()} で閉じる。
     *
     * @return 新しいキューマネージャー接続
     * @throws MQException 接続に失敗した場合
     */
    public MQQueueManager createConnection() throws MQException {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(MQConstants.HOST_NAME_PROPERTY, settings.getHost());
        properties.put(MQConstants.PORT_PROPERTY, settings.getPort());
        properties.put(MQConstants.CHANNEL_PROPERTY, settings.getChannel());
        properties.put(MQConstants.TRANSPORT_PROPERTY, MQConstants.TRANSPORT_MQSERIES_CLIENT);
        if (settings.getUser() != null && !settings.getUser().isBlank()) {
            properties.put(MQConstants.USER_ID_PROPERTY, settings.getUser());
        }
        if (settings.getPassword() != null && !settings.getPassword().isEmpty()) {
            properties.put(MQConstants.PASSWORD_PROPERTY, settings.getPassword());
        }
        customizers.forEach(customizer -> customizer.customize(properties));
        return new MQQueueManager(settings.getQueueManager(), properties);
    }
}
