package io.github.jdubois.bootui.autoconfigure.explorer;

import io.github.jdubois.bootui.core.dto.ExplorerEventDto;
import io.github.jdubois.bootui.core.dto.ExplorerReport;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only MVC binding; MCP calls these same service methods without an internal HTTP request. */
@RestController
@Import(SpringExplorerService.class)
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/explorer")
public class ExplorerController {
    private final SpringExplorerService service;

    public ExplorerController(SpringExplorerService service) {
        this.service = service;
    }

    @GetMapping
    public ExplorerReport report(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "since", defaultValue = "0") long since,
            @RequestParam(name = "limit", defaultValue = "0") int limit,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "until", required = false) Long until,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "pageSize", defaultValue = "0") int pageSize) {
        return service.report(type, severity, since, limit, q, until, cursor, pageSize);
    }

    @GetMapping("/events/{id}")
    public ExplorerEventDto event(
            @PathVariable("id") String id, @RequestParam(name = "timestamp", required = false) Long timestamp) {
        return service.event(id, timestamp);
    }

    public ExplorerEventDto event(String id) {
        return service.event(id, null);
    }
}
