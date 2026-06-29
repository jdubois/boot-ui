package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.ScheduledReport;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTasksService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Framework-neutral Scheduled Tasks controller. It serves {@code GET /bootui/api/scheduled} by delegating to
 * the engine {@link ScheduledTasksService}, which sorts and wraps the mapped, self-filtered tasks supplied by
 * the (optional) {@code ScheduledTaskProvider}.
 *
 * <p>Unlike the neutral {@code MappingsController}, this controller keeps its class-level
 * {@code @ConditionalOnClass(ScheduledTaskHolder)}: the panel's whole reason to exist is the scheduling
 * infrastructure, so when {@code org.springframework.scheduling.config.ScheduledTaskHolder} is absent the
 * endpoint should not be registered at all (byte-identical to the original controller, whose bean-presence
 * the autoconfiguration tests assert). The {@code org.springframework.scheduling.config.*} trigger types are
 * confined to the gated {@code SpringScheduledTaskProvider}, so this controller carries no scheduling import
 * itself.</p>
 */
@RestController
@ConditionalOnClass(name = "org.springframework.scheduling.config.ScheduledTaskHolder")
@RequestMapping("/bootui/api/scheduled")
public class ScheduledController {

    private final ScheduledTasksService scheduledTasksService;

    public ScheduledController(ScheduledTasksService scheduledTasksService) {
        this.scheduledTasksService = scheduledTasksService;
    }

    @GetMapping
    public ScheduledReport scheduled() {
        return scheduledTasksService.report();
    }
}
