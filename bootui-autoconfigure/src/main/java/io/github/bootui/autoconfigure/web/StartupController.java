package io.github.bootui.autoconfigure.web;

import io.github.bootui.core.BootUiDtos.StartupReport;
import io.github.bootui.core.BootUiDtos.StartupStepDto;
import io.github.bootui.core.BootUiDtos.TagDto;
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.startup.StartupEndpoint;
import org.springframework.boot.actuate.startup.StartupEndpoint.StartupDescriptor;
import org.springframework.boot.context.metrics.buffering.StartupTimeline.TimelineEvent;
import org.springframework.core.metrics.StartupStep;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/startup")
public class StartupController {

    private final ObjectProvider<StartupEndpoint> endpoint;

    public StartupController(ObjectProvider<StartupEndpoint> endpoint) {
        this.endpoint = endpoint;
    }

    @GetMapping
    public StartupReport startup() {
        StartupEndpoint se = endpoint.getIfAvailable();
        if (se == null) {
            return new StartupReport(List.of());
        }
        return toReport(se.startupSnapshot());
    }

    private StartupReport toReport(StartupDescriptor descriptor) {
        if (descriptor == null || descriptor.getTimeline() == null || descriptor.getTimeline().getEvents() == null) {
            return new StartupReport(List.of());
        }
        return new StartupReport(descriptor.getTimeline().getEvents().stream()
                .map(this::toStep)
                .toList());
    }

    private StartupStepDto toStep(TimelineEvent event) {
        StartupStep step = event.getStartupStep();
        return new StartupStepDto(
                step.getId(),
                step.getParentId(),
                step.getName(),
                durationMs(event),
                StreamSupport.stream(step.getTags().spliterator(), false)
                        .map(tag -> new TagDto(tag.getKey(), tag.getValue()))
                        .toList());
    }

    private long durationMs(TimelineEvent event) {
        return event.getDuration() == null ? 0L : Duration.parse(event.getDuration().toString()).toMillis();
    }
}
