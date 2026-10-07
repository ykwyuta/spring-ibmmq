package io.github.spring_ibmmq;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Spring bean のメソッドを IBM MQ キューの受信先として登録する。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IbmmqListener {
    /** 受信元のキュー名を指定する。 @return 受信元のキュー名。 */
    String value();
    /** 並列に受信するワーカー数を指定する。 @return 独立した接続で受信するワーカー数。 */
    int concurrency() default 1;
    /** MQGET の待ち時間を指定する。 @return 待ち時間（ミリ秒）。-1 は共通設定を使う。 */
    int waitInterval() default -1;
    /** MQGET のカスタマイザを指定する。 @return 適用する {@link IbmmqGetOptionsCustomizer} bean の名前。 */
    String getOptionsCustomizer() default "";
    /** listener 用の変換器を指定する。 @return 既定の変換器に代えて使う {@link IbmmqMessageConverter} bean の名前。 */
    String messageConverter() default "";
    /** 受信失敗時の処理を指定する。 @return 受信失敗時に呼ぶ {@link IbmmqListenerErrorHandler} bean の名前。 */
    String errorHandler() default "";
}
