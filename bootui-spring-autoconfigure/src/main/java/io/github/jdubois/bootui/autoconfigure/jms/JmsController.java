package io.github.jdubois.bootui.autoconfigure.jms;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.JmsReport;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import io.github.jdubois.bootui.engine.jms.JmsMessageDtos;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.NativeDetector;
import org.springframework.http.HttpStatus;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Read/clear API over the same JMS recorder that feeds Live Activity. */
@RestController
@ConditionalOnClass(name = {"org.springframework.jms.core.JmsTemplate", "jakarta.jms.Message"})
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/jms")
public class JmsController {

    private static final String NATIVE_IMAGE_UNAVAILABLE =
            "JMS capture is not available when running as a GraalVM native image";

    private final ObjectProvider<JmsActivityRecorder> recorderProvider;
    private final ObjectProvider<JmsTemplate> jmsTemplateProvider;
    private final BootUiProperties properties;

    public JmsController(
            ObjectProvider<JmsActivityRecorder> recorderProvider,
            ObjectProvider<JmsTemplate> jmsTemplateProvider,
            BootUiProperties properties) {
        this.recorderProvider = recorderProvider;
        this.jmsTemplateProvider = jmsTemplateProvider;
        this.properties = properties;
    }

    @GetMapping
    public JmsReport list() {
        if (nativeImageDetected()) {
            return JmsReport.unavailable(
                    NATIVE_IMAGE_UNAVAILABLE, properties.getJms().getMaxEntries());
        }
        JmsActivityRecorder recorder = availableRecorder();
        if (recorder == null) {
            return JmsReport.unavailable(
                    "No JmsTemplate bean is present", properties.getJms().getMaxEntries());
        }
        return JmsMessageDtos.toReport(recorder);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        JmsActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            recorder.clear();
        }
    }

    private JmsActivityRecorder availableRecorder() {
        if (jmsTemplateProvider.stream().findAny().isEmpty()) {
            return null;
        }
        return recorderProvider.getIfAvailable();
    }

    boolean nativeImageDetected() {
        return NativeDetector.inNativeImage();
    }
}
