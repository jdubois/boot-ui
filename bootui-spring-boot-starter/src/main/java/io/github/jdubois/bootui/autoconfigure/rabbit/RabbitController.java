package io.github.jdubois.bootui.autoconfigure.rabbit;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.RabbitReport;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitMessageDtos;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read/clear API for the dedicated RabbitMQ panel.
 *
 * <p>Available whenever a {@link RabbitTemplate} bean is present (the
 * {@code RabbitProducerCaptureBeanPostProcessor}/{@code RabbitConsumerCaptureBeanPostProcessor}
 * wrap it and feed captures into the shared {@link RabbitActivityRecorder}); when no
 * {@link RabbitTemplate} bean is present the panel reports itself unavailable, even though
 * {@link RabbitActivityRecorder} is itself framework-neutral and always registered — same shape
 * as {@code KafkaController}.</p>
 *
 * <p>This is a read-mostly view over the exact same recorder that already feeds Live Activity's
 * {@code MESSAGING} entries (there is only ever one buffer), so {@link #clear()} also clears
 * the RabbitMQ Live Activity history, and disabling this panel disables the underlying capture
 * entirely (see {@code BootUiEngineConfiguration.RabbitBackendConfiguration}).</p>
 */
@RestController
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/rabbitmq")
public class RabbitController {

    private final ObjectProvider<RabbitActivityRecorder> recorderProvider;
    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;
    private final BootUiProperties properties;

    public RabbitController(
            ObjectProvider<RabbitActivityRecorder> recorderProvider,
            ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
            BootUiProperties properties) {
        this.recorderProvider = recorderProvider;
        this.rabbitTemplateProvider = rabbitTemplateProvider;
        this.properties = properties;
    }

    @GetMapping
    public RabbitReport list() {
        RabbitActivityRecorder recorder = availableRecorder();
        if (recorder == null) {
            return RabbitReport.unavailable(
                    "No RabbitTemplate bean is present",
                    properties.getRabbitmq().getMaxEntries());
        }
        return RabbitMessageDtos.toReport(recorder);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        RabbitActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            recorder.clear();
        }
    }

    private RabbitActivityRecorder availableRecorder() {
        if (rabbitTemplateProvider.stream().findAny().isEmpty()) {
            return null;
        }
        return recorderProvider.getIfAvailable();
    }
}
