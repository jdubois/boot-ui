package io.github.jdubois.bootui.autoconfigure.jms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.Direction;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.Topic;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jms.core.JmsTemplate;

class JmsProducerCaptureBeanPostProcessorTests {

    @Test
    void ignoresBeansThatAreNotJmsTemplates() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(recorder), enabledProperties());

        Object bean = new Object();
        assertThat(postProcessor.postProcessAfterInitialization(bean, "someBean"))
                .isSameAs(bean);
    }

    @Test
    void skipsWrappingWhenRecorderDisabled() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(false, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(recorder), enabledProperties());
        JmsTemplate template = templateWithQueueSession("orders");

        Object result = postProcessor.postProcessAfterInitialization(template, "jmsTemplate");

        assertThat(result).isSameAs(template);
    }

    @Test
    void skipsWrappingWhenJmsDisabledViaProperties() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        BootUiProperties properties = new BootUiProperties();
        properties.getJms().setEnabled(false);
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(recorder), properties);
        JmsTemplate template = templateWithQueueSession("orders");

        Object result = postProcessor.postProcessAfterInitialization(template, "jmsTemplate");

        assertThat(result).isSameAs(template);
        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void capturesSuccessfulSendToNamedQueue() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(recorder), enabledProperties());
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");

        proxy.send("orders", session -> session.createTextMessage("hello"));

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.direction()).isEqualTo(Direction.PRODUCE);
        assertThat(message.topic()).isEqualTo("orders");
        assertThat(message.success()).isTrue();
        assertThat(message.durationMillis()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(message.partition()).isNull();
        assertThat(message.offset()).isNull();
    }

    @Test
    void capturesSuccessfulConvertAndSendToNamedQueue() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(recorder), enabledProperties());
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");

        proxy.convertAndSend("orders", "hello");

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.direction()).isEqualTo(Direction.PRODUCE);
        assertThat(message.topic()).isEqualTo("orders");
        assertThat(message.success()).isTrue();
    }

    @Test
    void capturesSuccessfulSendToDestinationObject() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(recorder), enabledProperties());
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");

        Queue queue = mock(Queue.class);
        when(queue.getQueueName()).thenReturn("orders");
        proxy.send(queue, session -> session.createTextMessage("hello"));

        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).topic()).isEqualTo("orders");
    }

    @Test
    void capturesSuccessfulSendToTopicDestination() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(recorder), enabledProperties());
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("events"), "jmsTemplate");

        Topic topic = mock(Topic.class);
        when(topic.getTopicName()).thenReturn("events");
        proxy.send(topic, session -> session.createTextMessage("hello"));

        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).topic()).isEqualTo("events");
    }

    @Test
    void capturesFailedSendWithErrorMessage() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(recorder), enabledProperties());
        JmsTemplate proxy =
                (JmsTemplate) postProcessor.postProcessAfterInitialization(templateWithFailingSession(), "jmsTemplate");

        assertThatThrownBy(() -> proxy.send("orders", session -> session.createTextMessage("hello")))
                .isInstanceOf(RuntimeException.class);

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.success()).isFalse();
        assertThat(message.errorMessage()).isNotNull();
    }

    @Test
    void doesNotRecordNonSendMethods() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(recorder), enabledProperties());
        JmsTemplate proxy = (JmsTemplate)
                postProcessor.postProcessAfterInitialization(templateWithQueueSession("orders"), "jmsTemplate");

        // getDefaultDestinationName() is not a send operation
        proxy.getDefaultDestinationName();

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void doesNotWrapWhenRecorderUnavailable() throws Exception {
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(null), enabledProperties());
        JmsTemplate template = templateWithQueueSession("orders");

        Object result = postProcessor.postProcessAfterInitialization(template, "jmsTemplate");

        assertThat(result).isSameAs(template);
    }

    @Test
    void doesNotDoubleRecordWhenConvertAndSendCallsSendInternally() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        JmsProducerCaptureBeanPostProcessor postProcessor =
                new JmsProducerCaptureBeanPostProcessor(provider(recorder), enabledProperties());
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

    // --- helpers ---

    /** Builds a JmsTemplate backed by a mock ConnectionFactory that produces a working Session. */
    private static JmsTemplate templateWithQueueSession(String queueName) throws Exception {
        jakarta.jms.ConnectionFactory cf = mock(jakarta.jms.ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        Queue queue = mock(Queue.class);
        Message message = mock(Message.class);
        MessageProducer producer = mock(MessageProducer.class);

        when(cf.createConnection()).thenReturn(connection);
        when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
        when(session.createQueue(queueName)).thenReturn(queue);
        when(queue.getQueueName()).thenReturn(queueName);
        when(session.createProducer(any(Destination.class))).thenReturn(producer);
        when(session.createTextMessage(any()))
                .thenReturn((jakarta.jms.TextMessage) mock(jakarta.jms.TextMessage.class));
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

    private static BootUiProperties enabledProperties() {
        return new BootUiProperties();
    }
}
