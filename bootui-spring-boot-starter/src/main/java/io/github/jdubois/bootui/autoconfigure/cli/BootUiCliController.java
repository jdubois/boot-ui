package io.github.jdubois.bootui.autoconfigure.cli;

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

/**
 * The command-line endpoint at {@code /bootui/api/cli}: the same tool registry the MCP server exposes,
 * projected onto plain REST so the {@code bootui} CLI and CI jobs can ask a running application one question
 * without speaking JSON-RPC or enabling the MCP server.
 *
 * <p>{@code GET} describes what this instance advertises. {@code POST /tools/{name}} invokes one tool and
 * returns its payload directly — no JSON-RPC envelope — with the outcome in the HTTP status, because a shell
 * reads status codes, not {@code isError} flags.
 *
 * <p>All policy lives in {@link CliService}, which dispatches through the same {@code McpDispatcher} logic as
 * MCP, so panel enable/read-only toggles, argument validation, result caps, concurrency, and timeouts are
 * inherited rather than reimplemented. The endpoint sits under {@code /bootui/api}, so the loopback, Host
 * allow-list, cross-site-write, and authentication-token filters apply unchanged.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/cli")
public class BootUiCliController {

    private final CliService service;

    public BootUiCliController(CliService service) {
        this.service = service;
    }

    @GetMapping
    public CliServerStatus status() {
        return service.status();
    }

    /**
     * Invokes one tool. The body is bound as a raw map, not a fixed argument record, so that every property
     * the caller sent reaches {@link CliService}: which arguments a tool accepts is decided by its schema, and
     * one it does not declare must be rejected rather than silently dropped during binding.
     */
    @PostMapping("/tools/{name}")
    public ResponseEntity<Object> invoke(
            @PathVariable String name, @RequestBody(required = false) Map<String, Object> arguments) {
        CliToolResponse response = service.invoke(name, arguments);
        return ResponseEntity.status(response.status().code())
                .body(response.successful() ? response.payload() : Map.of("error", response.error()));
    }
}
