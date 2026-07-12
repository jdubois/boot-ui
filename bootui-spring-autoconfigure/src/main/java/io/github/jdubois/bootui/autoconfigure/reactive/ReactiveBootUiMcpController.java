package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.mcp.BootUiMcpService;
import io.github.jdubois.bootui.autoconfigure.mcp.McpServerState;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Reactive WebFlux transport for the BootUI MCP server. */
@RestController
@RequestMapping("/bootui/api/mcp")
public class ReactiveBootUiMcpController {

    private static final String PAYLOAD_LIMIT_MESSAGE = "Request payload exceeds limit";

    private final BootUiMcpService service;
    private final ReactiveBootUiMcpTools tools;
    private final McpServerState state;
    private final int maxPayloadBytes;

    public ReactiveBootUiMcpController(
            BootUiMcpService service, ReactiveBootUiMcpTools tools, McpServerState state, BootUiProperties properties) {
        this.service = service;
        this.tools = tools;
        this.state = state;
        this.maxPayloadBytes = Math.max(1, properties.getMcp().getMaxPayloadBytes());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> rpc(@RequestBody byte[] requestBody) {
        return Mono.fromCallable(() -> handle(requestBody)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<ResponseEntity<String>> status() {
        ObjectNode status = JsonNodeFactory.instance.objectNode();
        status.put("server", McpProtocol.SERVER_NAME);
        status.put("enabled", state.isEnabled());
        status.put("transport", "http");
        status.put("endpoint", "/bootui/api/mcp");
        status.put("protocolVersion", McpProtocol.DEFAULT_PROTOCOL_VERSION);
        status.put("toolCount", tools.tools().size());
        tools.tools().forEach(tool -> status.withArray("tools").add(tool.name()));
        return Mono.just(json(200, status));
    }

    private ResponseEntity<String> handle(byte[] requestBody) {
        if (requestBody != null && requestBody.length > maxPayloadBytes) {
            return json(413, error(null, McpProtocol.PARSE_ERROR, PAYLOAD_LIMIT_MESSAGE));
        }
        JsonNode request;
        try {
            request = service.readTree(requestBody == null ? new byte[0] : requestBody);
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage()));
        }
        if (request != null && request.isArray()) {
            return json(400, error(null, McpProtocol.INVALID_REQUEST, McpProtocol.BATCH_NOT_SUPPORTED_MESSAGE));
        }
        if (!state.isEnabled()) {
            return json(200, disabledError(request));
        }
        JsonNode response = service.handle(request);
        if (response == null) {
            return ResponseEntity.accepted().build();
        }
        return json(200, response);
    }

    private static JsonNode disabledError(JsonNode request) {
        JsonNode id = request != null && request.isObject() ? request.get("id") : null;
        return error(id, McpProtocol.SERVER_DISABLED, McpProtocol.SERVER_DISABLED_MESSAGE);
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.put("code", code);
        error.put("message", message == null ? "Error" : message);
        response.set("error", error);
        return response;
    }

    private static ResponseEntity<String> json(int status, JsonNode body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString());
    }
}
