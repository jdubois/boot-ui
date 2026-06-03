package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.DevServiceLogReport;
import io.github.jdubois.bootui.core.dto.DevServiceRestartResult;
import io.github.jdubois.bootui.core.dto.DevServicesReport;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing Docker Compose and Testcontainers dev services in the BootUI sidebar.
 *
 * <p>Listens for context events to capture the dynamic state of dev services
 * launched by Spring Boot during startup.</p>
 */
@RestController
@RequestMapping("/bootui/api/dev-services")
public class DevServicesController implements ApplicationListener<ApplicationEvent> {

    private final DevServicesService service;

    public DevServicesController(ConfigurableApplicationContext applicationContext, BootUiProperties properties) {
        this.service = new DevServicesService(applicationContext, properties);
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        service.onApplicationEvent(event);
    }

    @GetMapping
    public DevServicesReport list() {
        return service.list();
    }

    @GetMapping("/{id}/logs")
    public DevServiceLogReport logs(@PathVariable String id) {
        return service.logs(id);
    }

    @PostMapping("/{id}/restart")
    public DevServiceRestartResult restart(@PathVariable String id) {
        return service.restart(id);
    }
}
