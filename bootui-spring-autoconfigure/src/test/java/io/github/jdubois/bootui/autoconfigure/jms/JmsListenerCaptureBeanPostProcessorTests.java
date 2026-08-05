package io.github.jdubois.bootui.autoconfigure.jms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.jms.JmsListenerCaptureBeanPostProcessor.CapturingMessageListener;
import io.github.jdubois.bootui.autoconfigure.jms.JmsListenerCaptureBeanPostProcessor.CapturingSessionAwareMessageListener;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder.Direction;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.SessionAwareMessageListener;

class JmsListenerCaptureBeanPostProcessorTests {

    @Test
    void ignoresBeansThatAreNotListenerContainerFactories() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsListenerCaptureBeanPostProcessor postProcessor = new JmsListenerCaptureBeanPostProcessor(provider(recorder));

        Object bean = new Object();
        assertThat(postProcessor.postProcessAfterInitialization(bean, "someBean"))
                .isSameAs(bean);
    }

    @Test
    void skipsWrappingWhenRecorderDisabled() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(false, true, 10, 50);
        JmsListenerCaptureBeanPostProcessor postProcessor = new JmsListenerCaptureBeanPostProcessor(provider(recorder));
        DefaultJmsListenerContainerFactory factory = factoryWithMockConnection();

        Object result = postProcessor.postProcessAfterInitialization(factory, "myJmsListenerContainerFactory");

        assertThat(result).isSameAs(factory);
    }

    @Test
    void doesNotWrapWhenRecorderUnavailable() throws Exception {
        JmsListenerCaptureBeanPostProcessor postProcessor = new JmsListenerCaptureBeanPostProcessor(provider(null));
        DefaultJmsListenerContainerFactory factory = factoryWithMockConnection();

        Object result = postProcessor.postProcessAfterInitialization(factory, "myJmsListenerContainerFactory");

        assertThat(result).isSameAs(factory);
    }

    // --- CapturingMessageListenerAdapter unit tests ---

    @Test
    void capturesSuccessfulDeliveryViaMessageListener() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        Message message = messageWithQueueDestination("orders");
        jakarta.jms.MessageListener delegate = mock(jakarta.jms.MessageListener.class);
        CapturingMessageListener adapter = new CapturingMessageListener(delegate, recorder, null, "myFactory");

        adapter.onMessage(message);

        verify(delegate).onMessage(message);
        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage captured = recorder.recent().get(0);
        assertThat(captured.direction()).isEqualTo(Direction.CONSUME);
        assertThat(captured.destination()).isEqualTo("orders");
        assertThat(captured.success()).isTrue();
        assertThat(captured.durationMillis()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(captured.listenerId()).isEqualTo("myFactory");
    }

    @Test
    void capturesSuccessfulDeliveryViaSessionAwareListener() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        Message message = messageWithQueueDestination("orders");
        Session session = mock(Session.class);
        @SuppressWarnings("unchecked")
        SessionAwareMessageListener<Message> delegate = mock(SessionAwareMessageListener.class);
        CapturingSessionAwareMessageListener adapter =
                new CapturingSessionAwareMessageListener(delegate, recorder, null, "myFactory");

        adapter.onMessage(message, session);

        verify(delegate).onMessage(message, session);
        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage captured = recorder.recent().get(0);
        assertThat(captured.direction()).isEqualTo(Direction.CONSUME);
        assertThat(captured.destination()).isEqualTo("orders");
        assertThat(captured.success()).isTrue();
        assertThat(captured.listenerId()).isEqualTo("myFactory");
    }

    @Test
    void capturesFailedDeliveryAndRethrowsException() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        Message message = messageWithQueueDestination("orders");
        jakarta.jms.MessageListener delegate = mock(jakarta.jms.MessageListener.class);
        RuntimeException boom = new IllegalStateException("listener failed");
        org.mockito.Mockito.doThrow(boom).when(delegate).onMessage(message);
        CapturingMessageListener adapter = new CapturingMessageListener(delegate, recorder, null, "myFactory");

        assertThatThrownBy(() -> adapter.onMessage(message)).isSameAs(boom);

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage captured = recorder.recent().get(0);
        assertThat(captured.success()).isFalse();
        assertThat(captured.failureType()).isEqualTo("IllegalStateException");
    }

    @Test
    void composesWithExistingMessageListenerWithoutDoubleInvocation() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        Message message = messageWithQueueDestination("orders");
        jakarta.jms.MessageListener delegate = mock(jakarta.jms.MessageListener.class);
        CapturingMessageListener adapter = new CapturingMessageListener(delegate, recorder, null, "myFactory");

        adapter.onMessage(message);

        // Delegate is invoked exactly once, not twice.
        verify(delegate, times(1)).onMessage(message);
    }

    @Test
    void preservesSessionAwareDispatchPath() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        Message message = messageWithQueueDestination("orders");
        Session session = mock(Session.class);
        @SuppressWarnings("unchecked")
        SessionAwareMessageListener<Message> delegate = mock(SessionAwareMessageListener.class);
        CapturingSessionAwareMessageListener adapter =
                new CapturingSessionAwareMessageListener(delegate, recorder, null, "myFactory");

        // Container calls onMessage(msg, session) when the adapter implements SessionAwareMessageListener
        adapter.onMessage(message, session);

        // The SessionAware delegate receives the session, not just the message.
        verify(delegate).onMessage(message, session);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void hashesMessageIdWhenCaptureEnabled() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        Message message = messageWithQueueDestination("orders");
        when(message.getJMSMessageID()).thenReturn("ID:unique-message-id");
        jakarta.jms.MessageListener delegate = mock(jakarta.jms.MessageListener.class);
        CapturingMessageListener adapter = new CapturingMessageListener(delegate, recorder, null, "myFactory");

        adapter.onMessage(message);

        List<CapturedMessage> messages = recorder.recent();
        assertThat(messages.get(0).messageId())
                .isNotNull()
                .doesNotContain("ID:unique-message-id"); // must be hashed, not raw
    }

    @Test
    void nullMessageIdRemainsNull() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        Message message = messageWithQueueDestination("orders");
        when(message.getJMSMessageID()).thenReturn(null);
        jakarta.jms.MessageListener delegate = mock(jakarta.jms.MessageListener.class);
        CapturingMessageListener adapter = new CapturingMessageListener(delegate, recorder, null, "myFactory");

        adapter.onMessage(message);

        assertThat(recorder.recent().get(0).messageId()).isNull();
    }

    @Test
    void proxyWrapsTheListenerAndCapturesSanitizedEndpointMetadata() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsListenerCaptureBeanPostProcessor postProcessor = new JmsListenerCaptureBeanPostProcessor(provider(recorder));
        DefaultJmsListenerContainerFactory factory = factoryWithMockConnection();
        DefaultJmsListenerContainerFactory proxy =
                (DefaultJmsListenerContainerFactory) postProcessor.postProcessAfterInitialization(factory, "myFactory");
        jakarta.jms.MessageListener delegate = mock(jakarta.jms.MessageListener.class);
        SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId("ordersListener");
        endpoint.setDestination("orders");
        endpoint.setSubscription("orders-sub?token=raw-secret");
        endpoint.setMessageListener(delegate);

        AbstractMessageListenerContainer container = proxy.createListenerContainer(endpoint);

        Object listener = container.getMessageListener();
        assertThat(listener)
                .isInstanceOf(CapturingMessageListener.class)
                .isNotInstanceOf(SessionAwareMessageListener.class);
        ((jakarta.jms.MessageListener) listener).onMessage(messageWithQueueDestination("orders"));
        CapturedMessage captured = recorder.recent().get(0);
        assertThat(captured.subscriptionName()).isEqualTo("orders-sub?token=******");
        assertThat(captured.listenerId()).isEqualTo("ordersListener");
    }

    @Test
    void doesNotDoubleWrapAnAlreadyProcessedFactory() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsListenerCaptureBeanPostProcessor postProcessor = new JmsListenerCaptureBeanPostProcessor(provider(recorder));
        DefaultJmsListenerContainerFactory factory = factoryWithMockConnection();

        Object once = postProcessor.postProcessAfterInitialization(factory, "myFactory");
        Object twice = postProcessor.postProcessAfterInitialization(once, "myFactory");

        assertThat(twice).isSameAs(once);
    }

    @Test
    void preservesErrorIdentityAndDoesNotExposeItsMessage() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        Message message = messageWithQueueDestination("orders?token=raw-secret");
        jakarta.jms.MessageListener delegate = mock(jakarta.jms.MessageListener.class);
        AssertionError failure = new AssertionError("payload-secret");
        org.mockito.Mockito.doThrow(failure).when(delegate).onMessage(message);
        CapturingMessageListener adapter = new CapturingMessageListener(delegate, recorder, null, "myFactory");

        assertThatThrownBy(() -> adapter.onMessage(message)).isSameAs(failure);

        CapturedMessage captured = recorder.recent().get(0);
        assertThat(captured.destination()).isEqualTo("orders?token=******");
        assertThat(captured.failureType()).isEqualTo("AssertionError").doesNotContain("payload-secret");
    }

    // --- helpers ---

    private static DefaultJmsListenerContainerFactory factoryWithMockConnection() throws Exception {
        jakarta.jms.ConnectionFactory cf = mock(jakarta.jms.ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(cf.createConnection()).thenReturn(connection);
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(cf);
        return factory;
    }

    private static Message messageWithQueueDestination(String queueName) throws Exception {
        Message message = mock(Message.class);
        Queue queue = mock(Queue.class);
        when(queue.getQueueName()).thenReturn(queueName);
        when(message.getJMSDestination()).thenReturn(queue);
        when(message.getJMSMessageID()).thenReturn(null);
        return message;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
