package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.mcp.SpringMcpPayloads;
import io.github.jdubois.bootui.core.dto.CliServerStatus;
import io.github.jdubois.bootui.engine.cli.CliService;
import io.github.jdubois.bootui.engine.cli.CliToolResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Reactive WebFlux transport for the command-line endpoint at {@code /bootui/api/cli}.
 *
 * <p>Identical contract to the servlet {@code BootUiCliController} — same {@link CliService}, same statuses —
 * differing only in that tool invocation is offloaded to {@link Schedulers#boundedElastic()}. BootUI's tools
 * call blocking diagnostics (JMX, JDBC metadata, heap inspection), so running them inline would occupy an
 * event-loop thread, exactly as the reactive MCP transport already avoids.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/cli")
public class ReactiveBootUiCliController {

    private final CliService service;
    private final ObjectMapper mapper;

    public ReactiveBootUiCliController(CliService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public Mono<CliServerStatus> status() {
        return Mono.fromSupplier(service::status).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/tools/{name}")
    public Mono<ResponseEntity<Object>> invoke(
            @PathVariable String name, @RequestBody(required = false) Map<String, Object> arguments) {
        return Mono.fromSupplier(() -> {
                    CliToolResponse response = service.invoke(name, arguments);
                    return ResponseEntity.status(response.status().code())
                            .body(
                                    response.successful()
                                            ? SpringMcpPayloads.render(response.payload(), mapper)
                                            : (Object) Map.of("error", response.error()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
