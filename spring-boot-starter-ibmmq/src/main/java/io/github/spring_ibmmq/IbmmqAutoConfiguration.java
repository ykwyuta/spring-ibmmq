package io.github.spring_ibmmq;

import com.ibm.mq.MQQueueManager;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;

/** IBM MQ が有効なときに native API 用の bean を登録する自動構成。 */
@AutoConfiguration
@ConditionalOnClass(MQQueueManager.class)
@ConditionalOnProperty(prefix = "ibmmq", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IbmmqProperties.class)
public class IbmmqAutoConfiguration {
    /** Spring Boot が自動構成を生成する。 */
    public IbmmqAutoConfiguration() {
    }

    /**
     * 接続設定と利用側のカスタマイザから接続ファクトリーを作る。
     *
     * @param properties {@code ibmmq.*} の設定
     * @param customizers 接続プロパティを変更する bean
     * @return 接続ファクトリー
     */
    @Bean
    @ConditionalOnMissingBean
    IbmmqConnectionFactory ibmmqConnectionFactory(IbmmqProperties properties,
            ObjectProvider<IbmmqConnectionPropertiesCustomizer> customizers) {
        return new IbmmqConnectionFactory(properties, customizers.orderedStream().toList());
    }

    /**
     * 文字列、バイト列、MQMessage の既定変換器を作る。
     *
     * @return 既定のメッセージ変換器
     */
    @Bean
    @ConditionalOnMissingBean(IbmmqMessageConverter.class)
    IbmmqMessageConverter ibmmqMessageConverter() {
        return new SimpleIbmmqMessageConverter();
    }

    /**
     * 送信用の {@code ibmmqTemplate} bean を作る。
     *
     * @param connectionFactory 接続ファクトリー
     * @param converter メッセージ変換器
     * @return 送信テンプレート
     */
    @Bean(name = "ibmmqTemplate", destroyMethod = "close")
    @ConditionalOnMissingBean(IbmmqTemplate.class)
    IbmmqTemplate ibmmqTemplate(IbmmqConnectionFactory connectionFactory, IbmmqMessageConverter converter) {
        return new IbmmqTemplate(connectionFactory, converter);
    }

    /**
     * アノテーション付きメソッドを検出する listener registry を作る。
     *
     * @param context Spring のアプリケーションコンテキスト
     * @param connectionFactory 接続ファクトリー
     * @param properties listener の待機設定
     * @param converter 既定のメッセージ変換器
     * @return listener registry
     */
    @Bean
    @ConditionalOnMissingBean
    IbmmqListenerRegistry ibmmqListenerRegistry(ApplicationContext context,
            IbmmqConnectionFactory connectionFactory, IbmmqProperties properties, IbmmqMessageConverter converter) {
        return new IbmmqListenerRegistry(context, connectionFactory, properties, converter);
    }
}
