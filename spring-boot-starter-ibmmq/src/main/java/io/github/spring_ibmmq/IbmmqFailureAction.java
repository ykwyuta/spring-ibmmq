package io.github.spring_ibmmq;

/** listener の処理失敗後に行う MQ syncpoint 操作。 */
public enum IbmmqFailureAction {
    /** メッセージを元のキューへ戻し、再配送を許可する。 */
    REQUEUE,
    /** MQGET を確定し、このキューからメッセージを取り除く。 */
    DISCARD
}
