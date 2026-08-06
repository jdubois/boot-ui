package io.github.jdubois.bootui.autoconfigure.rabbit;

import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import java.util.ArrayList;
import java.util.List;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Prepends a timing {@link MethodInterceptor} to every
 * {@link AbstractRabbitListenerContainerFactory} bean's advice chain after initialization so
 * every {@code @RabbitListener} delivery is recorded into {@link RabbitActivityRecorder} —
 * the consume-side twin of {@link RabbitProducerCaptureBeanPostProcessor}.
 *
 * <p>{@link AbstractRabbitListenerContainerFactory#setAdviceChain} is the canonical Spring AMQP
 * extensibility hook for listener interceptors. We read the existing chain via the public
 * {@link AbstractRabbitListenerContainerFactory#getAdviceChain} getter and prepend our timing
 * interceptor, then set it back — composing with, not replacing, any existing application-owned
 * advice. If reading or setting the advice chain fails for any reason, that factory is left
 * unwrapped (deliveries from its containers will not be captured) but the application still
 * starts and other factories are processed normally.</p>
 *
 * <p>The timing advice wraps the {@code MessageListener.onMessage(Message)} invocation
 * dispatched by the listener container: it reads routing metadata from
 * {@link MessageProperties} (exchange, routing key, queue, correlation ID), records start
 * nanos, calls {@code proceed()}, and records success or failure with duration. Only metadata
 * is captured, never the message body/payload.</p>
 *
 * <p>Recording is fail-open: any failure while reading metadata or recording the outcome is
 * caught and logged at warn, and {@code proceed()} is still invoked afterward.</p>
 */
public final class RabbitConsumerCaptureBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RabbitConsumerCaptureBeanPostProcessor.class);

    private final ObjectProvider<RabbitActivityRecorder> recorderProvider;

    public RabbitConsumerCaptureBeanPostProcessor(ObjectProvider<RabbitActivityRecorder> recorderProvider) {
        this.recorderProvider = recorderProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof AbstractRabbitListenerContainerFactory<?> factory)) {
            return bean;
        }
        RabbitActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return bean;
        }
        try {
            Advice[] existing = factory.getAdviceChain();
            Advice[] newChain = prependAdvice(new CapturingConsumerAdvice(recorder), existing);
            factory.setAdviceChain(newChain);
        } catch (RuntimeException ex) {
            log.warn(
                    "BootUI could not enable RabbitMQ consumer capture for listener container factory bean "
                            + "'{}'; leaving it unwrapped",
                    beanName,
                    ex);
        }
        return bean;
    }

    private static Advice[] prependAdvice(MethodInterceptor first, Advice[] rest) {
        if (rest == null || rest.length == 0) {
            return new Advice[] {first};
        }
        Advice[] result = new Advice[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }

    /**
     * Times each {@code MessageListener.onMessage(Message)} invocation and records the outcome
     * into the recorder. Metadata is read from the {@link MessageProperties} of the AMQP
     * {@link Message} passed by the listener container.
     */
    private static final class CapturingConsumerAdvice implements MethodInterceptor {

        private final RabbitActivityRecorder recorder;

        private CapturingConsumerAdvice(RabbitActivityRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            long startNanos = System.nanoTime();
            List<Message> messages = extractMessages(invocation);
            try {
                Object result = invocation.proceed();
                recordOutcomes(messages, startNanos, true, null);
                return result;
            } catch (Throwable ex) {
                recordOutcomes(messages, startNanos, false, ex.getMessage());
                throw ex;
            }
        }

        private void recordOutcomes(List<Message> messages, long startNanos, boolean success, String errorMessage) {
            if (messages.isEmpty()) {
                recordOutcome(null, startNanos, success, errorMessage);
                return;
            }
            messages.forEach(message -> recordOutcome(message, startNanos, success, errorMessage));
        }

        private void recordOutcome(Message message, long startNanos, boolean success, String errorMessage) {
            try {
                long durationMillis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                MessageProperties props = message == null ? null : message.getMessageProperties();
                String exchange = props == null ? null : props.getReceivedExchange();
                String routingKey = props == null ? null : props.getReceivedRoutingKey();
                String queue = props == null ? null : props.getConsumerQueue();
                if (queue == null && props != null) {
                    queue = props.getConsumerQueue();
                }
                String correlationId = props == null ? null : props.getCorrelationId();
                recorder.recordConsume(
                        exchange, routingKey, queue, durationMillis, success, errorMessage, correlationId);
            } catch (RuntimeException ex) {
                log.warn("BootUI could not capture an incoming AMQP message; leaving it untouched", ex);
            }
        }

        private static List<Message> extractMessages(MethodInvocation invocation) {
            Object[] args = invocation.getArguments();
            if (args == null) {
                return List.of();
            }
            List<Message> messages = new ArrayList<>();
            for (Object arg : args) {
                if (arg instanceof Message message) {
                    messages.add(message);
                } else if (arg instanceof Iterable<?> iterable) {
                    for (Object element : iterable) {
                        if (element instanceof Message message) {
                            messages.add(message);
                        }
                    }
                }
            }
            return messages;
        }
    }
}
