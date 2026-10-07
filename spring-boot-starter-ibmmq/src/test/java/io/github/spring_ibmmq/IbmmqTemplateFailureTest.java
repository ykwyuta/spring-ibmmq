package io.github.spring_ibmmq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mock は IBM MQ 呼び出しから例外を発生させる試験に限る。 */
class IbmmqTemplateFailureTest {
    @Test
    void connectionFailureDoesNotAttemptToCloseMissingManager() throws Exception {
        IbmmqConnectionFactory factory = mock(IbmmqConnectionFactory.class);
        when(factory.createConnection()).thenThrow(mqFailure());
        assertThatThrownBy(() -> new IbmmqTemplate(factory).send("DEV.QUEUE.1", "body"))
                .isInstanceOf(IbmmqException.class);
    }

    @Test
    void syncpointPutMqFailureBacksOut() throws Exception {
        FailureFixture fixture = new FailureFixture();
        doThrow(mqFailure()).when(fixture.queue).put(any(MQMessage.class), any(MQPutMessageOptions.class));
        assertThatThrownBy(() -> fixture.template.send("DEV.QUEUE.1", message -> {},
                options -> options.options |= MQConstants.MQPMO_SYNCPOINT))
                .isInstanceOf(IbmmqException.class);
        verify(fixture.manager).backout();
    }

    @Test
    void syncpointPutRuntimeFailureBacksOut() throws Exception {
        FailureFixture fixture = new FailureFixture();
        doThrow(new IllegalStateException("injected")).when(fixture.queue)
                .put(any(MQMessage.class), any(MQPutMessageOptions.class));
        assertThatThrownBy(() -> fixture.template.send("DEV.QUEUE.1", message -> {},
                options -> options.options |= MQConstants.MQPMO_SYNCPOINT))
                .isInstanceOf(IllegalStateException.class);
        verify(fixture.manager).backout();
    }

    @Test
    void runtimeFailureBeforePutDoesNotBackout() throws Exception {
        FailureFixture fixture = new FailureFixture();
        assertThatThrownBy(() -> fixture.template.send("DEV.QUEUE.1", message -> {
            throw new IllegalStateException("injected");
        }, options -> {})).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mqGetFailureOtherThanTimeoutIsReported() throws Exception {
        FailureFixture fixture = new FailureFixture();
        doThrow(mqFailure()).when(fixture.queue).get(any(MQMessage.class), any(MQGetMessageOptions.class));
        assertThatThrownBy(() -> fixture.template.receive("DEV.QUEUE.1", 0))
                .isInstanceOf(IbmmqException.class);
    }

    private static MQException mqFailure() {
        return new MQException(MQConstants.MQCC_FAILED, MQConstants.MQRC_NOT_AUTHORIZED, null);
    }

    private static final class FailureFixture {
        final IbmmqConnectionFactory factory = mock(IbmmqConnectionFactory.class);
        final MQQueueManager manager = mock(MQQueueManager.class);
        final MQQueue queue = mock(MQQueue.class);
        final IbmmqTemplate template = new IbmmqTemplate(factory);

        FailureFixture() throws Exception {
            when(factory.createConnection()).thenReturn(manager);
            when(manager.accessQueue(anyString(), anyInt())).thenReturn(queue);
        }
    }
}
