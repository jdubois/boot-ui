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
@RequestMapping("/bootui/api/metrics")
public class MetricsController {

    private final MetricsReportProvider provider;

    public MetricsController(MetricsReportProvider provider) {
        this.provider = provider;
    }

    @GetMapping
    public MetricsReport metrics() {
        return provider.metrics();
    }

    @GetMapping("/detail")
    public MetricDetailDto metric(
            @RequestParam String name, @RequestParam(name = "tag", required = false) List<String> tagFilters) {
        return provider.metric(name, tagFilters);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }
}
