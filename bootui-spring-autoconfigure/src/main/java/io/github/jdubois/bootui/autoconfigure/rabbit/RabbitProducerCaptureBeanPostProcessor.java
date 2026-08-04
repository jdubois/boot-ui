package io.github.jdubois.bootui.autoconfigure.rabbit;

import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Correlation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Installs a {@link MessagePostProcessor} on every {@link RabbitTemplate} bean after
 * initialization so every publish is recorded into {@link RabbitActivityRecorder} — pass-through
 * by default, exactly like {@code KafkaProducerCaptureBeanPostProcessor} wraps
 * {@code KafkaTemplate} beans.
 *
 * <p>Spring AMQP calls the 4-arg
 * {@code MessagePostProcessor.postProcessMessage(message, correlationData, exchange, routingKey)}
 * overload inside {@code RabbitTemplate.doSend}, so our processor receives the actual per-send
 * exchange and routing key as method arguments — no {@code DirectFieldAccessor} needed.
 * {@link RabbitTemplate#addBeforePublishPostProcessors} is public and additive, so we can
 * append our processor without reading or replacing any existing one.</p>
 *
 * <p><strong>Only metadata is captured, never the message body/payload.</strong> The
 * processor returns the original message object unchanged.</p>
 *
 * <p>Duration is always {@code null} for publishes: the
 * {@code beforePublishPostProcessor} hook runs immediately before the actual
 * {@code basicPublish} and there is no post-send callback without publisher confirms.</p>
 *
 * <p>Recording is fail-open: any failure while reading metadata or recording is caught and
 * logged at warn, and the message is still returned unchanged so the publish can continue.</p>
 */
public final class RabbitProducerCaptureBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RabbitProducerCaptureBeanPostProcessor.class);

    private final ObjectProvider<RabbitActivityRecorder> recorderProvider;

    public RabbitProducerCaptureBeanPostProcessor(ObjectProvider<RabbitActivityRecorder> recorderProvider) {
        this.recorderProvider = recorderProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof RabbitTemplate template)) {
            return bean;
        }
        RabbitActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return bean;
        }
        template.addBeforePublishPostProcessors(new CapturingMessagePostProcessor(recorder));
        return bean;
    }

    /**
     * Records each publish into the recorder. The 4-arg overload is used so Spring AMQP passes
     * the actual per-send exchange and routing key; the 1-arg overload is kept as a fallback for
     * any caller that bypasses the 4-arg dispatch.
     */
    private static final class CapturingMessagePostProcessor implements MessagePostProcessor {

        private final RabbitActivityRecorder recorder;

        private CapturingMessagePostProcessor(RabbitActivityRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public Message postProcessMessage(Message message) throws AmqpException {
            // Fallback invoked when the caller does not use the 4-arg overload; capture without
            // per-send routing metadata (exchange/routingKey will be null).
            capturePublish(message, null, null);
            return message;
        }

        @Override
        public Message postProcessMessage(Message message, Correlation correlation) throws AmqpException {
            capturePublish(message, null, null);
            return message;
        }

        @Override
        public Message postProcessMessage(Message message, Correlation correlation, String exchange, String routingKey)
                throws AmqpException {
            // Primary interception path: Spring AMQP passes the actual per-send exchange and
            // routing key here, so we capture the full routing metadata for every publish.
            capturePublish(message, exchange, routingKey);
            return message;
        }

        private void capturePublish(Message message, String exchange, String routingKey) {
            try {
                String correlationId =
                        message == null ? null : message.getMessageProperties().getCorrelationId();
                recorder.recordPublish(
                        exchange,
                        routingKey,
                        null, // no post-send timing seam without publisher confirms
                        true,
                        null,
                        correlationId);
            } catch (RuntimeException ex) {
                log.warn("BootUI could not capture an outgoing AMQP message; leaving it untouched", ex);
            }
        }
    }
}
