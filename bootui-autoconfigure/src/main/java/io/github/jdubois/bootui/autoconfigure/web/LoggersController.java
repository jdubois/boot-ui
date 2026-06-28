package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.LoggerDto;
import io.github.jdubois.bootui.core.dto.LoggersReport;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Loggers panel.
 *
 * <p>This is a thin transport adapter: the framework-neutral reading, self-logger filtering, sorting,
 * paging and write guard all live in the engine {@link LoggersService}, which is shared with the
 * Quarkus adapter. The Actuator-specific access is concentrated in the gated
 * {@code SpringLoggerProvider}; when no logging backend is available the service returns an empty
 * report and rejects level changes.</p>
 */
@RestController
@RequestMapping("/bootui/api/loggers")
public class LoggersController {

    private final LoggersService loggers;

    public LoggersController(LoggersService loggers) {
        this.loggers = loggers;
    }

    @GetMapping
    public LoggersReport loggers(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return loggers.report(query, offset, limit);
    }

    @PostMapping("/{name}")
    public LoggerDto setLevel(@PathVariable String name, @RequestBody LevelUpdateRequest request) {
        return loggers.setLevel(name, request == null ? null : request.level());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }

    public record LevelUpdateRequest(String level) {}
}
