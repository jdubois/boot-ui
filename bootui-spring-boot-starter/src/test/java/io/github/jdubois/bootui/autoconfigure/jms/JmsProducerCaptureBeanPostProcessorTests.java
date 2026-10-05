package io.github.jdubois.bootui.autoconfigure.jms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder.Direction;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jms.core.JmsTemplate;

class JmsProducerCaptureBeanPostProcessorTests {

    @Test
    void ignoresBeansThatAreNotJmsTemplates() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));

        Object bean = new Object();
        assertThat(postProcessor.postProcessAfterInitialization(bean, "someBean"))
                .isSameAs(bean);
    }

    @Test
    void skipsWrappingWhenRecorderDisabled() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(false, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate template = templateWithQueueSession("orders");

        Object result = postProcessor.postProcessAfterInitialization(template, "jmsTemplate");

        assertThat(result).isSameAs(template);
    }

    @Test
    void capturesSuccessfulSendToNamedQueue() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");

        proxy.send("orders", session -> session.createTextMessage("hello"));

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.direction()).isEqualTo(Direction.PRODUCE);
        assertThat(message.destination()).isEqualTo("orders");
        assertThat(message.success()).isTrue();
        assertThat(message.durationMillis()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    void capturesSuccessfulConvertAndSendToNamedQueue() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");

        proxy.convertAndSend("orders", "hello");

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.direction()).isEqualTo(Direction.PRODUCE);
        assertThat(message.destination()).isEqualTo("orders");
        assertThat(message.success()).isTrue();
    }

    @Test
    void capturesSuccessfulSendToDestinationObject() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");

        Queue queue = mock(Queue.class);
        when(queue.getQueueName()).thenReturn("orders");
        proxy.send(queue, session -> session.createTextMessage("hello"));

        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).destination()).isEqualTo("orders");
    }

    @Test
    void capturesSuccessfulSendToTopicDestination() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("events"), "jmsTemplate");

        Topic topic = mock(Topic.class);
        when(topic.getTopicName()).thenReturn("events");
        proxy.send(topic, session -> session.createTextMessage("hello"));

        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).destination()).isEqualTo("events");
    }

    @Test
    void capturesFailedSendWithErrorMessage() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy =
                (JmsTemplate) postProcessor.postProcessAfterInitialization(templateWithFailingSession(), "jmsTemplate");

        assertThatThrownBy(() -> proxy.send("orders", session -> session.createTextMessage("hello")))
                .isInstanceOf(RuntimeException.class);

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.success()).isFalse();
        assertThat(message.failureType()).isNotNull();
    }

    @Test
    void doesNotRecordNonSendMethods() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");

        // getDefaultDestinationName() is not a send operation
        proxy.getDefaultDestinationName();

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void doesNotWrapWhenRecorderUnavailable() throws Exception {
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(null));
        JmsTemplate template = templateWithQueueSession("orders");

        Object result = postProcessor.postProcessAfterInitialization(template, "jmsTemplate");

        assertThat(result).isSameAs(template);
    }

    @Test
    void doesNotDoubleRecordWhenConvertAndSendCallsSendInternally() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");

        // convertAndSend calls send internally on the TARGET (not the proxy), so there is no
        // double-recording: only the convertAndSend interception fires.
        proxy.convertAndSend("orders", "hello");

        assertThat(recorder.recent()).hasSize(1);
        List<CapturedMessage> messages = recorder.recent();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).direction()).isEqualTo(Direction.PRODUCE);
    }

    @Test
    void hashesTheProviderAssignedMessageIdForMessageCreatorSends() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 16);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");
        TextMessage message = mock(TextMessage.class);
        when(message.getJMSMessageID()).thenReturn("ID:provider-assigned");

        proxy.send("orders", session -> message);

        assertThat(recorder.recent().get(0).messageId())
                .isNotNull()
                .doesNotContain("ID:provider-assigned")
                .hasSize(16);
    }

    @Test
    void capturesTheMessageReturnedByAnApplicationPostProcessor() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 16);
        TextMessage converted = mock(TextMessage.class);
        TextMessage processed = mock(TextMessage.class);
        when(processed.getJMSMessageID()).thenReturn("ID:processed");
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy = (JmsTemplate) postProcessor.postProcessAfterInitialization(
                templateWithQueueSession("orders", converted), "jmsTemplate");

        proxy.convertAndSend("orders", "payload", message -> processed);

        assertThat(recorder.recent().get(0).messageId())
                .isNotNull()
                .doesNotContain("ID:processed")
                .hasSize(16);
    }

    @Test
    void sanitizesDestinationMetadataAndNeverFallsBackToProviderToString() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");
        Destination providerDestination = mock(Destination.class);
        when(providerDestination.toString()).thenThrow(new AssertionError("must not expose provider metadata"));

        proxy.send("orders?password=raw-secret", session -> mock(Message.class));
        proxy.send(providerDestination, session -> mock(Message.class));

        assertThat(recorder.recent()).extracting(CapturedMessage::destination).contains("orders?password=******");
        assertThat(recorder.recent()).extracting(CapturedMessage::destination).anyMatch(java.util.Objects::isNull);
    }

    @Test
    void captureFailureNeverChangesSendBehavior() throws Exception {
        JmsActivityRecorder recorder = mock(JmsActivityRecorder.class);
        when(recorder.isEnabled()).thenReturn(true);
        doThrow(new IllegalStateException("capture failed"))
                .when(recorder)
                .recordProduce(any(), any(), any(), org.mockito.ArgumentMatchers.eq(true), any());
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");

        proxy.send("orders", session -> mock(Message.class));
    }

    @Test
    void doesNotDoubleWrapAnAlreadyProcessedTemplate() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor = new JmsProducerCaptureBeanPostProcessor(provider(recorder));
        JmsTemplate template = templateWithQueueSession("orders");

        Object once = postProcessor.postProcessAfterInitialization(template, "jmsTemplate");
        Object twice = postProcessor.postProcessAfterInitialization(once, "jmsTemplate");

        assertThat(twice).isSameAs(once);
    }

    // --- helpers ---

    /** Builds a JmsTemplate backed by a mock ConnectionFactory that produces a working Session. */
    private static JmsTemplate templateWithQueueSession(String queueName) throws Exception {
        return templateWithQueueSession(queueName, mock(TextMessage.class));
    }

    private static JmsTemplate templateWithQueueSession(String queueName, TextMessage convertedMessage)
            throws Exception {
        jakarta.jms.ConnectionFactory cf = mock(jakarta.jms.ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        Message message = mock(Message.class);
        MessageProducer producer = mock(MessageProducer.class);

        when(cf.createConnection()).thenReturn(connection);
        when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
        when(session.createQueue(anyString())).thenAnswer(invocation -> {
            Queue queue = mock(Queue.class);
            when(queue.getQueueName()).thenReturn(invocation.getArgument(0));
            return queue;
        });
        when(session.createProducer(any(Destination.class))).thenReturn(producer);
        when(session.createTextMessage(any())).thenReturn(convertedMessage);
        when(session.createObjectMessage(any())).thenReturn(mock(jakarta.jms.ObjectMessage.class));

        JmsTemplate template = new JmsTemplate(cf);
        template.setDefaultDestinationName(queueName);
        return template;
    }

    /** Builds a JmsTemplate whose ConnectionFactory throws on createConnection(). */
    private static JmsTemplate templateWithFailingSession() throws Exception {
        jakarta.jms.ConnectionFactory cf = mock(jakarta.jms.ConnectionFactory.class);
        when(cf.createConnection()).thenThrow(new jakarta.jms.JMSException("broker unavailable"));
        return new JmsTemplate(cf);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
