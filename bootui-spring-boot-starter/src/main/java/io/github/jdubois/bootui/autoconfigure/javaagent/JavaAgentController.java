package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Java Agent panel ({@code docs/PLAN-v2.md} §5.13), shared by the Spring MVC and WebFlux adapters: whether the BootUI
 * agent is attached, who holds its claim, and how to attach it. A pure read of the bootstrap bridge's status: it claims,
 * installs, and sends nothing.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/java-agent")
public class JavaAgentController {

    private final JavaAgentService service;

    public JavaAgentController(JavaAgentService service) {
        this.service = service;
    }

    @GetMapping
    public JavaAgentReport report() {
        return service.report();
    }
}
