package io.github.jdubois.bootui.autoconfigure.jms;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.Topic;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jms.config.AbstractJmsListenerContainerFactory;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.SessionAwareMessageListener;

/**
 * Wraps every {@link AbstractJmsListenerContainerFactory} bean with a CGLIB proxy after
 * initialization so that every container the factory subsequently creates (for each
 * {@code @JmsListener} endpoint) has its message listener wrapped with a
 * {@link CapturingMessageListenerAdapter} that times each delivery and records the outcome into
 * {@link KafkaActivityRecorder} as a {@code MESSAGING} entry — the consumer-side twin of
 * {@link JmsProducerCaptureBeanPostProcessor}.
 *
 * <p>{@link AbstractJmsListenerContainerFactory} has no {@code RecordInterceptor} equivalent (unlike
 * Kafka's {@code AbstractKafkaListenerContainerFactory}), so the factory itself is proxied via
 * {@link ProxyFactory} to intercept {@code createListenerContainer(JmsListenerEndpoint)} calls.
 * After the factory creates the container and sets its message listener (via
 * {@code setupListenerContainer}), the interceptor wraps the listener in a
 * {@link CapturingMessageListenerAdapter} and sets it back. The adapter implements both
 * {@link jakarta.jms.MessageListener} and {@link SessionAwareMessageListener} so the container's
 * own {@code instanceof} dispatch always reaches the correct path regardless of the delegate
 * type.</p>
 *
 * <p>Composing: the adapter delegates to the original listener without replacing it. If the
 * original listener was a {@link SessionAwareMessageListener}, it is called with the full session;
 * if it was a plain {@link jakarta.jms.MessageListener}, it is called message-only. The error
 * handler set on the factory (and therefore on each container) is <em>not</em> wrapped: failures
 * are recorded inside the adapter's {@code try/catch} before rethrowing, so the error handler
 * still runs once and undisturbed after the adapter records the outcome — no double-counting.</p>
 *
 * <p>Recording is fail-open: any failure while wrapping a factory or while extracting metadata
 * from a message is caught and logged at warn, leaving the factory or delivery untouched — the
 * same guarantee the Kafka consumer post-processor provides.</p>
 */
public final class JmsListenerCaptureBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(JmsListenerCaptureBeanPostProcessor.class);

    private final ObjectProvider<KafkaActivityRecorder> recorderProvider;
    private final BootUiProperties properties;

    public JmsListenerCaptureBeanPostProcessor(
            ObjectProvider<KafkaActivityRecorder> recorderProvider, BootUiProperties properties) {
        this.recorderProvider = recorderProvider;
        this.properties = properties;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof AbstractJmsListenerContainerFactory<?> factory)) {
            return bean;
        }
        if (!properties.getJms().isEnabled()) {
            return bean;
        }
        KafkaActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return bean;
        }
        try {
            ProxyFactory proxyFactory = new ProxyFactory(factory);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.addAdvice(new FactoryInterceptor(recorder, beanName));
            return proxyFactory.getProxy();
        } catch (RuntimeException ex) {
            log.warn(
                    "BootUI could not enable JMS consumer capture for listener container factory bean "
                            + "'{}'; leaving it unwrapped",
                    beanName,
                    ex);
            return bean;
        }
    }

    /**
     * Intercepts {@code createListenerContainer(JmsListenerEndpoint)} on the factory proxy and
     * wraps the returned container's message listener with {@link CapturingMessageListenerAdapter}.
     */
    private static final class FactoryInterceptor implements MethodInterceptor {

        private final KafkaActivityRecorder recorder;
        private final String factoryBeanName;

        private FactoryInterceptor(KafkaActivityRecorder recorder, String factoryBeanName) {
            this.recorder = recorder;
            this.factoryBeanName = factoryBeanName;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            if (!"createListenerContainer".equals(invocation.getMethod().getName())) {
                return invocation.proceed();
            }
            AbstractMessageListenerContainer container = (AbstractMessageListenerContainer) invocation.proceed();
            try {
                Object existing = container.getMessageListener();
                if (existing != null) {
                    container.setMessageListener(
                            new CapturingMessageListenerAdapter(existing, recorder, factoryBeanName));
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "BootUI could not wrap the message listener for a JMS container created by "
                                + "factory bean '{}'; leaving it unwrapped",
                        factoryBeanName,
                        ex);
            }
            return container;
        }
    }

    /**
     * Wraps any listener — plain {@link jakarta.jms.MessageListener} or
     * {@link SessionAwareMessageListener} — to time each delivery and record the outcome into the
     * shared recorder. Implements both listener interfaces so the container's own
     * {@code instanceof} dispatch is satisfied regardless of the delegate's actual type; the
     * {@link SessionAwareMessageListener} path is preferred by the container's dispatch and is
     * always entered, delegating to the appropriate interface based on the wrapped listener type.
     *
     * <p>Failures are recorded inside the {@code catch} block before rethrowing, so the
     * container's own error-handler still fires once undisturbed — no double-recording.</p>
     */
    static final class CapturingMessageListenerAdapter
            implements jakarta.jms.MessageListener, SessionAwareMessageListener<Message> {

        private final Object delegate;
        private final KafkaActivityRecorder recorder;
        private final String listenerId;

        CapturingMessageListenerAdapter(Object delegate, KafkaActivityRecorder recorder, String listenerId) {
            this.delegate = delegate;
            this.recorder = recorder;
            this.listenerId = listenerId;
        }

        /**
         * The container always calls {@link #onMessage(Message, Session)} first (because this
         * adapter implements {@link SessionAwareMessageListener}); this no-arg fallback is only
         * reached when an external caller invokes {@link jakarta.jms.MessageListener} directly.
         */
        @Override
        public void onMessage(Message message) {
            long start = System.nanoTime();
            String destination = destinationOf(message);
            String messageId = messageIdOf(message);
            try {
                if (delegate instanceof jakarta.jms.MessageListener ml) {
                    ml.onMessage(message);
                } else if (delegate instanceof SessionAwareMessageListener<?>) {
                    @SuppressWarnings("unchecked")
                    SessionAwareMessageListener<Message> sal = (SessionAwareMessageListener<Message>) delegate;
                    sal.onMessage(message, null);
                }
                long durationMillis = (System.nanoTime() - start) / 1_000_000L;
                recorder.recordJmsConsume(destination, messageId, durationMillis, true, null, null, listenerId);
            } catch (Throwable ex) {
                long durationMillis = (System.nanoTime() - start) / 1_000_000L;
                recorder.recordJmsConsume(
                        destination, messageId, durationMillis, false, ex.getMessage(), null, listenerId);
                if (ex instanceof RuntimeException re) throw re;
                throw new RuntimeException(ex);
            }
        }

        /**
         * Primary dispatch path: the container calls this because the adapter implements
         * {@link SessionAwareMessageListener}, which the container prefers over plain
         * {@link jakarta.jms.MessageListener}.
         */
        @Override
        public void onMessage(Message message, @Nullable Session session) throws JMSException {
            long start = System.nanoTime();
            String destination = destinationOf(message);
            String messageId = messageIdOf(message);
            try {
                if (delegate instanceof SessionAwareMessageListener<?>) {
                    @SuppressWarnings("unchecked")
                    SessionAwareMessageListener<Message> sal = (SessionAwareMessageListener<Message>) delegate;
                    sal.onMessage(message, session);
                } else if (delegate instanceof jakarta.jms.MessageListener ml) {
                    ml.onMessage(message);
                }
                long durationMillis = (System.nanoTime() - start) / 1_000_000L;
                recorder.recordJmsConsume(destination, messageId, durationMillis, true, null, null, listenerId);
            } catch (JMSException | RuntimeException ex) {
                long durationMillis = (System.nanoTime() - start) / 1_000_000L;
                recorder.recordJmsConsume(
                        destination, messageId, durationMillis, false, ex.getMessage(), null, listenerId);
                throw ex;
            }
        }

        private static String destinationOf(Message message) {
            try {
                Destination dest = message.getJMSDestination();
                if (dest instanceof Queue q) return q.getQueueName();
                if (dest instanceof Topic t) return t.getTopicName();
                return dest == null ? null : dest.toString();
            } catch (JMSException ex) {
                return null;
            }
        }

        private static String messageIdOf(Message message) {
            try {
                return message.getJMSMessageID();
            } catch (JMSException ex) {
                return null;
            }
        }
    }
}
