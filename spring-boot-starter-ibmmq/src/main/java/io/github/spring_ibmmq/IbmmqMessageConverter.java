package io.github.spring_ibmmq;

import com.ibm.mq.MQMessage;
import java.io.IOException;

/** native MQMessage とアプリケーションのペイロードを相互変換する。 */
public interface IbmmqMessageConverter {
    /**
     * ペイロードを送信用メッセージへ書き込む。
     *
     * @param payload 送信する値
     * @param message 書き込み先
     * @throws IOException 本文の書き込みに失敗した場合
     */
    void write(Object payload, MQMessage message) throws IOException;

    /**
     * 受信したメッセージを指定された型へ変換する。
     *
     * @param message 受信したメッセージ
     * @param targetType 受信メソッドの引数型
     * @return 変換した値
     * @throws IOException 本文の読み取りに失敗した場合
     */
    Object read(MQMessage message, Class<?> targetType) throws IOException;

    /**
     * 指定された受信型を扱えるか返す。
     *
     * @param targetType 受信メソッドの引数型
     * @return 変換可能なら {@code true}
     */
    boolean supportsRead(Class<?> targetType);
}
