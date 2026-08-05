package io.github.jdubois.bootui.autoconfigure.jms;

import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.NativeDetector;
import org.springframework.jms.config.AbstractJmsListenerContainerFactory;
import org.springframework.jms.config.AbstractJmsListenerEndpoint;
import org.springframework.jms.config.JmsListenerEndpoint;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.SessionAwareMessageListener;

/**
 * Wraps every {@link AbstractJmsListenerContainerFactory} bean with a CGLIB proxy after
 * initialization so that every container the factory subsequently creates (for each
 * {@code @JmsListener} endpoint) has its message listener wrapped with a
 * capture adapter that times each delivery and records the outcome into
 * {@link JmsActivityRecorder} as a {@code MESSAGING} entry — the consumer-side twin of
 * {@link JmsProducerCaptureBeanPostProcessor}.
 *
 * <p>{@link AbstractJmsListenerContainerFactory} has no {@code RecordInterceptor} equivalent (unlike
 * Kafka's {@code AbstractKafkaListenerContainerFactory}), so the factory itself is proxied via
 * {@link ProxyFactory} to intercept {@code createListenerContainer(JmsListenerEndpoint)} calls.
 * After the factory creates the container and sets its message listener (via
 * {@code setupListenerContainer}), the interceptor wraps the listener in a
 * matching capture adapter and sets it back. Plain {@link jakarta.jms.MessageListener} and
 * {@link SessionAwareMessageListener} delegates use separate adapters, preserving the container's
 * original dispatch path.</p>
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

    private final ObjectProvider<JmsActivityRecorder> recorderProvider;

    public JmsListenerCaptureBeanPostProcessor(ObjectProvider<JmsActivityRecorder> recorderProvider) {
        this.recorderProvider = recorderProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (NativeDetector.inNativeImage() || !(bean instanceof AbstractJmsListenerContainerFactory<?> factory)) {
            return bean;
        }
        JmsActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled() || isAlreadyWrapped(factory)) {
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

    private static boolean isAlreadyWrapped(Object bean) {
        if (!(bean instanceof Advised advised)) {
            return false;
        }
        return java.util.Arrays.stream(advised.getAdvisors())
                .anyMatch(advisor -> advisor.getAdvice() instanceof FactoryInterceptor);
    }

    /**
     * Intercepts {@code createListenerContainer(JmsListenerEndpoint)} on the factory proxy and
     * wraps the returned container's message listener with a matching capture adapter.
     */
    private static final class FactoryInterceptor implements MethodInterceptor {

        private final JmsActivityRecorder recorder;
        private final String factoryBeanName;

        private FactoryInterceptor(JmsActivityRecorder recorder, String factoryBeanName) {
            this.recorder = recorder;
            this.factoryBeanName = factoryBeanName;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            if (!"createListenerContainer".equals(invocation.getMethod().getName())) {
                return invocation.proceed();
            }
            Object result = invocation.proceed();
            if (!(result instanceof AbstractMessageListenerContainer container)) {
                return result;
            }
            try {
                ListenerMetadata metadata = listenerMetadata(invocation, factoryBeanName);
                Object existing = container.getMessageListener();
                Object capturing = capturingListener(existing, recorder, metadata);
                if (capturing != existing) {
                    container.setMessageListener(capturing);
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "BootUI could not wrap the message listener for a JMS container created by "
                                + "factory bean '{}'; leaving it unwrapped",
                        factoryBeanName,
                        ex);
            }
            return result;
        }

        private static Object capturingListener(
                Object delegate, JmsActivityRecorder recorder, ListenerMetadata metadata) {
            if (delegate instanceof CapturingMessageListener
                    || delegate instanceof CapturingSessionAwareMessageListener) {
                return delegate;
            }
            if (delegate instanceof SessionAwareMessageListener<?> sessionAware) {
                @SuppressWarnings("unchecked")
                SessionAwareMessageListener<Message> typed = (SessionAwareMessageListener<Message>) sessionAware;
                return new CapturingSessionAwareMessageListener(
                        typed, recorder, metadata.subscriptionName(), metadata.listenerId());
            }
            if (delegate instanceof jakarta.jms.MessageListener messageListener) {
                return new CapturingMessageListener(
                        messageListener, recorder, metadata.subscriptionName(), metadata.listenerId());
            }
            return delegate;
        }

        private static ListenerMetadata listenerMetadata(MethodInvocation invocation, String factoryBeanName) {
            String listenerId = JmsCaptureMetadata.listenerId(factoryBeanName);
            String subscriptionName = null;
            Object[] arguments = invocation.getArguments();
            if (arguments.length == 0 || !(arguments[0] instanceof JmsListenerEndpoint endpoint)) {
                return new ListenerMetadata(subscriptionName, listenerId);
            }
            try {
                String endpointId = JmsCaptureMetadata.listenerId(endpoint.getId());
                if (endpointId != null) {
                    listenerId = endpointId;
                }
                if (endpoint instanceof AbstractJmsListenerEndpoint abstractEndpoint) {
                    subscriptionName = JmsCaptureMetadata.subscriptionName(abstractEndpoint.getSubscription());
                }
            } catch (RuntimeException ignored) {
                // Keep capture active with the sanitized factory bean name when custom endpoint metadata fails.
            }
            return new ListenerMetadata(subscriptionName, listenerId);
        }
    }

    private record ListenerMetadata(String subscriptionName, String listenerId) {}

    /**
     * Wraps a plain {@link jakarta.jms.MessageListener} without changing its listener-interface
     * shape. Failures are recorded before the same throwable is rethrown, so the container's own
     * error handler still fires once undisturbed.
     */
    static final class CapturingMessageListener implements jakarta.jms.MessageListener {

        private final jakarta.jms.MessageListener delegate;
        private final JmsActivityRecorder recorder;
        private final String subscriptionName;
        private final String listenerId;

        CapturingMessageListener(
                jakarta.jms.MessageListener delegate,
                JmsActivityRecorder recorder,
                String subscriptionName,
                String listenerId) {
            this.delegate = delegate;
            this.recorder = recorder;
            this.subscriptionName = subscriptionName;
            this.listenerId = listenerId;
        }

        @Override
        public void onMessage(Message message) {
            long start = System.nanoTime();
            try {
                delegate.onMessage(message);
                safeRecord(message, start, true, null);
            } catch (Throwable ex) {
                safeRecord(message, start, false, JmsCaptureMetadata.failureType(ex));
                throw ex;
            }
        }

        private void safeRecord(Message message, long start, boolean success, String failureType) {
            record(recorder, subscriptionName, listenerId, message, start, success, failureType);
        }
    }

    static final class CapturingSessionAwareMessageListener implements SessionAwareMessageListener<Message> {

        private final SessionAwareMessageListener<Message> delegate;
        private final JmsActivityRecorder recorder;
        private final String subscriptionName;
        private final String listenerId;

        CapturingSessionAwareMessageListener(
                SessionAwareMessageListener<Message> delegate,
                JmsActivityRecorder recorder,
                String subscriptionName,
                String listenerId) {
            this.delegate = delegate;
            this.recorder = recorder;
            this.subscriptionName = subscriptionName;
            this.listenerId = listenerId;
        }

        @Override
        public void onMessage(Message message, @Nullable Session session) throws JMSException {
            long start = System.nanoTime();
            try {
                delegate.onMessage(message, session);
                record(recorder, subscriptionName, listenerId, message, start, true, null);
            } catch (JMSException | RuntimeException | Error ex) {
                record(
                        recorder,
                        subscriptionName,
                        listenerId,
                        message,
                        start,
                        false,
                        JmsCaptureMetadata.failureType(ex));
                throw ex;
            }
        }
    }

    private static void record(
            JmsActivityRecorder recorder,
            String subscriptionName,
            String listenerId,
            Message message,
            long start,
            boolean success,
            String failureType) {
        try {
            long durationMillis = (System.nanoTime() - start) / 1_000_000L;
            recorder.recordConsume(
                    JmsCaptureMetadata.destination(message),
                    JmsCaptureMetadata.messageId(message),
                    durationMillis,
                    success,
                    failureType,
                    subscriptionName,
                    listenerId);
        } catch (RuntimeException ex) {
            log.warn("BootUI could not capture an incoming JMS message; leaving it untouched", ex);
        }
    }
}
