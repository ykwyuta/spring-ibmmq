package io.github.spring_ibmmq;

import com.ibm.mq.MQMessage;

/** 受信メソッドの失敗を処理し、メッセージの処遇を決める。 */
@FunctionalInterface
public interface IbmmqListenerErrorHandler {
    /**
     * 受信メソッドの例外を処理する。
     *
     * @param message 受信した native メッセージ
     * @param failure 変換または受信メソッドで発生した失敗
     * @return 再配送か破棄かを表す処理。{@code null} は使用しない
     */
    IbmmqFailureAction onError(MQMessage message, Exception failure);
}
