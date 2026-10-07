package io.github.spring_ibmmq;

/** IBM MQ の送信処理などで発生した失敗を表す非検査例外。 */
public class IbmmqException extends RuntimeException {
    /**
     * 失敗内容と元の例外を保持する。
     *
     * @param message 失敗した操作の説明
     * @param cause 元の例外
     */
    public IbmmqException(String message, Throwable cause) {
        super(message, cause);
    }
}
