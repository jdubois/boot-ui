package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.BootUiDtos.LoggerDto;
import io.github.jdubois.bootui.core.BootUiDtos.LoggersReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint.LoggerLevelsDescriptor;
import org.springframework.boot.actuate.logging.LoggersEndpoint.LoggersDescriptor;
import org.springframework.boot.actuate.logging.LoggersEndpoint.SingleLoggerLevelsDescriptor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bootui/api/loggers")
public class LoggersController {

    private final ObjectProvider<LoggersEndpoint> endpoint;

    private final BootUiSelfDataFilter selfDataFilter;

    public LoggersController(ObjectProvider<LoggersEndpoint> endpoint) {
        this(endpoint, BootUiSelfDataFilter.defaults());
    }

    @Autowired
    public LoggersController(ObjectProvider<LoggersEndpoint> endpoint, BootUiSelfDataFilter selfDataFilter) {
        this.endpoint = endpoint;
        this.selfDataFilter = selfDataFilter;
    }

    @GetMapping
    public LoggersReport loggers(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        LoggersEndpoint le = endpoint.getIfAvailable();
        if (le == null) {
            return new LoggersReport(List.of(), List.of());
        }
        LoggersDescriptor descriptor = le.loggers();
        List<String> levels = new ArrayList<>();
        if (descriptor.getLevels() != null) {
            for (LogLevel l : descriptor.getLevels()) {
                levels.add(l.name());
            }
        }
        List<LoggerDto> loggers = new ArrayList<>();
        Map<String, LoggerLevelsDescriptor> map = descriptor.getLoggers();
        if (map != null) {
            for (Map.Entry<String, LoggerLevelsDescriptor> e : map.entrySet()) {
                if (!selfDataFilter.shouldIncludeLogger(e.getKey())) {
                    continue;
                }
                String configured = e.getValue().getConfiguredLevel();
                String effective = configured;
                if (e.getValue() instanceof SingleLoggerLevelsDescriptor single) {
                    effective = single.getEffectiveLevel();
                }
                loggers.add(new LoggerDto(e.getKey(), configured, effective));
            }
        }
        loggers.sort(Comparator.comparing(LoggerDto::name));
        String normalizedQuery = PagedList.normalize(query);
        PagedList.Result<LoggerDto> page =
                PagedList.from(loggers, logger -> PagedList.contains(logger.name(), normalizedQuery), offset, limit);
        return new LoggersReport(levels, page.items(), page.page());
    }

    @PostMapping("/{name}")
    public LoggerDto setLevel(@PathVariable String name, @RequestBody LevelUpdateRequest request) {
        LoggersEndpoint le = endpoint.getIfAvailable();
        if (le == null) {
            throw new IllegalStateException("LoggersEndpoint is not available");
        }
        LogLevel level =
                request == null || request.level() == null || request.level().isBlank()
                        ? null
                        : LogLevel.valueOf(request.level().toUpperCase());
        le.configureLogLevel(name, level);
        LoggerLevelsDescriptor descriptor = le.loggerLevels(name);
        String configured = descriptor.getConfiguredLevel();
        String effective = configured;
        if (descriptor instanceof SingleLoggerLevelsDescriptor single) {
            effective = single.getEffectiveLevel();
        }
        return new LoggerDto(name, configured, effective);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }

    public record LevelUpdateRequest(String level) {}
}
