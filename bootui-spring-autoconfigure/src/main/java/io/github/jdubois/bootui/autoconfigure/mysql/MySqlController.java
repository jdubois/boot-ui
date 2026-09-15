package io.github.jdubois.bootui.autoconfigure.mysql;

import io.github.jdubois.bootui.core.dto.MySqlInsightReport;
import io.github.jdubois.bootui.engine.mysql.MySqlInsightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shared MVC/WebFlux binding; all collection and cached-report policy belongs to the engine. */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/mysql")
public class MySqlController {

    private final MySqlInsightService service;

    public MySqlController(MySqlInsightService service) {
        this.service = service;
    }

    @GetMapping
    public MySqlInsightReport report() {
        return service.report();
    }

    @PostMapping("/read")
    public MySqlInsightReport read() {
        return service.read();
    }
}
