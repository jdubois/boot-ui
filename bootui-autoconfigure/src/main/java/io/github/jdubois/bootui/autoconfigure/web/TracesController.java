package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.core.dto.TracesReport;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.engine.telemetry.TracesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only API for the BootUI Traces panel. Thin Spring adapter over the framework-neutral
 * {@link TracesService} in {@code bootui-engine}.
 */
@RestController
@RequestMapping("/bootui/api/traces")
public class TracesController {

    private final TracesService service;

    public TracesController(TelemetryStore store, BootUiProperties properties) {
        this(store, properties, BootUiSelfDataFilter.defaults());
    }

    @Autowired
    public TracesController(TelemetryStore store, BootUiProperties properties, BootUiSelfDataFilter selfDataFilter) {
        this.service =
                new TracesService(store, new SpringTelemetrySettings(properties), selfDataFilter.telemetryClassifier());
    }

    @GetMapping
    public TracesReport list(@RequestParam(name = "limit", required = false, defaultValue = "100") int limit) {
        return service.list(limit);
    }

    @GetMapping("/{traceId}")
    public TraceDetailDto detail(@PathVariable String traceId) {
        return service.detail(traceId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "trace " + traceId + " not found"));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        service.clear();
    }
}
