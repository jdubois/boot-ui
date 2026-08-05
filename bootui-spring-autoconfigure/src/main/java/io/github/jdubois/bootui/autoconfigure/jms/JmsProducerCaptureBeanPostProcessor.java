package io.github.jdubois.bootui.autoconfigure.jms;

import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import jakarta.jms.Destination;
import jakarta.jms.Message;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.NativeDetector;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.jms.core.MessagePostProcessor;

/**
 * Wraps every {@link JmsTemplate} bean with a CGLIB proxy after initialization so every
 * {@code send}/{@code convertAndSend} call is timed and recorded into {@link JmsActivityRecorder}
 * as a {@code MESSAGING} entry, before delegating to the original template — pass-through by
 * default, exactly like {@link io.github.jdubois.bootui.autoconfigure.kafka.KafkaProducerCaptureBeanPostProcessor}
 * wraps {@code KafkaTemplate} beans.
 *
 * <p>{@link JmsTemplate} has no {@code ProducerListener} equivalent, so a CGLIB proxy via
 * {@link ProxyFactory} is used instead: the proxy intercepts the public {@code send} and
 * {@code convertAndSend} methods by name, extracts the destination from the first argument (String
 * name → sanitized; {@code Destination} → queue/topic name extracted; no explicit destination →
 * the template's configured {@code defaultDestinationName}), and delegates to the original
 * template via {@link MethodInvocation#proceed()}. Because {@link ProxyFactory} delegates to the
 * original target, any {@code this.send()} call that {@code convertAndSend} makes internally goes
 * to the <em>original</em> template, not the proxy — so there is no double-recording.</p>
 *
 * <p><strong>Only send metadata is captured, never the message payload.</strong> The JMS message
 * body is an arbitrary, potentially large and sensitive application object with no generic masking
 * strategy; only a one-way hash of the JMS message ID is retained as a correlation handle when a
 * {@code MessageCreator} or {@code MessagePostProcessor} exposes the created message, and only when
 * {@code captureKey} is enabled on the shared recorder. Duration is always measured since the send is synchronous —
 * unlike Kafka's async {@code ProducerListener} callback where no start timestamp is available.
 * </p>
 *
 * <p>Recording is fail-open: any failure while wrapping a bean or while extracting metadata is
 * caught and logged at warn, and the template is always returned unwrapped rather than
 * dropped — mirroring the Kafka producer post-processor's equivalent guarantee.</p>
 */
public final class JmsProducerCaptureBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(JmsProducerCaptureBeanPostProcessor.class);

    private final ObjectProvider<JmsActivityRecorder> recorderProvider;

    public JmsProducerCaptureBeanPostProcessor(ObjectProvider<JmsActivityRecorder> recorderProvider) {
        this.recorderProvider = recorderProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (NativeDetector.inNativeImage() || !(bean instanceof JmsTemplate template)) {
            return bean;
        }
        JmsActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled() || isAlreadyWrapped(template)) {
            return bean;
        }
        try {
            ProxyFactory proxyFactory = new ProxyFactory(template);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.addAdvice(new JmsProducerCaptureInterceptor(template, recorder));
            return proxyFactory.getProxy();
        } catch (RuntimeException ex) {
            log.warn(
                    "BootUI could not enable JMS producer capture for JmsTemplate bean '{}'; leaving it unwrapped",
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
                .anyMatch(advisor -> advisor.getAdvice() instanceof JmsProducerCaptureInterceptor);
    }

    /**
     * Times each {@code send}/{@code convertAndSend} call, records the outcome into the shared
     * messaging recorder, then delegates to the original template.
     */
    private static final class JmsProducerCaptureInterceptor implements MethodInterceptor {

        private final JmsTemplate target;
        private final JmsActivityRecorder recorder;

        private JmsProducerCaptureInterceptor(JmsTemplate target, JmsActivityRecorder recorder) {
            this.target = target;
            this.recorder = recorder;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            String methodName = invocation.getMethod().getName();
            if (!isSendMethod(methodName)) {
                return invocation.proceed();
            }
            String destination = extractDestinationName(invocation);
            AtomicReference<Message> sentMessage = wrapMessageCallbacks(invocation);
            long start = System.nanoTime();
            try {
                Object result = invocation.proceed();
                long durationMillis = (System.nanoTime() - start) / 1_000_000L;
                String messageDestination = JmsCaptureMetadata.destination(sentMessage.get());
                safeRecord(
                        messageDestination == null ? destination : messageDestination,
                        JmsCaptureMetadata.messageId(sentMessage.get()),
                        durationMillis,
                        true,
                        null);
                return result;
            } catch (Throwable ex) {
                long durationMillis = (System.nanoTime() - start) / 1_000_000L;
                safeRecord(
                        destination,
                        JmsCaptureMetadata.messageId(sentMessage.get()),
                        durationMillis,
                        false,
                        JmsCaptureMetadata.failureType(ex));
                throw ex;
            }
        }

        private static boolean isSendMethod(String methodName) {
            return methodName.equals("send")
                    || methodName.equals("convertAndSend")
                    || methodName.equals("sendAndReceive")
                    || methodName.equals("convertSendAndReceive");
        }

        private static AtomicReference<Message> wrapMessageCallbacks(MethodInvocation invocation) {
            AtomicReference<Message> message = new AtomicReference<>();
            Class<?>[] parameterTypes = invocation.getMethod().getParameterTypes();
            Object[] arguments = invocation.getArguments();
            for (int i = 0; i < parameterTypes.length; i++) {
                if (MessageCreator.class.isAssignableFrom(parameterTypes[i])
                        && arguments[i] instanceof MessageCreator delegate) {
                    arguments[i] = (MessageCreator) session -> {
                        Message created = delegate.createMessage(session);
                        message.set(created);
                        return created;
                    };
                } else if (MessagePostProcessor.class.isAssignableFrom(parameterTypes[i])
                        && arguments[i] instanceof MessagePostProcessor delegate) {
                    arguments[i] = (MessagePostProcessor) original -> {
                        Message processed = delegate.postProcessMessage(original);
                        message.set(processed);
                        return processed;
                    };
                }
            }
            return message;
        }

        private void safeRecord(
                String destination, String messageId, long durationMillis, boolean success, String failureType) {
            try {
                recorder.recordProduce(destination, messageId, durationMillis, success, failureType);
            } catch (RuntimeException ex) {
                log.warn("BootUI could not capture an outgoing JMS message; leaving it untouched", ex);
            }
        }

        private String extractDestinationName(MethodInvocation invocation) {
            Method method = invocation.getMethod();
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args = invocation.getArguments();
            if (paramTypes.length > 0) {
                if (paramTypes[0] == String.class) {
                    return JmsCaptureMetadata.sanitize((String) args[0]);
                }
                if (Destination.class.isAssignableFrom(paramTypes[0]) && args[0] instanceof Destination dest) {
                    return JmsCaptureMetadata.destination(dest);
                }
            }
            // No destination argument: use the template's configured default destination name.
            // getDefaultDestinationName() returns null when a Destination object was set instead
            // of a name; in that case, fall back to extracting the name from the Destination.
            try {
                String defaultName = target.getDefaultDestinationName();
                if (defaultName != null) {
                    return JmsCaptureMetadata.sanitize(defaultName);
                }
                Destination defaultDest = target.getDefaultDestination();
                return JmsCaptureMetadata.destination(defaultDest);
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }
}
