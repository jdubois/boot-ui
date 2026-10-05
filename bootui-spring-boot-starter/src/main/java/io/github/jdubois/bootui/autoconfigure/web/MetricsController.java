package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.MetricDetailDto;
import io.github.jdubois.bootui.core.dto.MetricsReport;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/metrics")
public class MetricsController {

    private final MetricsReportProvider provider;

    public MetricsController(MetricsReportProvider provider) {
        this.provider = provider;
    }

    @GetMapping
    public MetricsReport metrics(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "group", required = false) String group,
            @RequestParam(name = "provenance", required = false) String provenance,
            @RequestParam(name = "explanation", required = false) String explanation,
            @RequestParam(name = "offset", required = false) String offset,
            @RequestParam(name = "limit", required = false) String limit) {
        return provider.metrics(query, type, group, provenance, explanation, offset, limit);
    }

    @GetMapping("/detail")
    public MetricDetailDto metric(
            @RequestParam(required = false) String name,
            @RequestParam(name = "tag", required = false) List<String> tagFilters,
            @RequestParam(name = "offset", required = false) String offset,
            @RequestParam(name = "limit", required = false) String limit) {
        return provider.metric(name, tagFilters, offset, limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }
}
