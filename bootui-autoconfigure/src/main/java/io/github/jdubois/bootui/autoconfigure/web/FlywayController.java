package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.FlywayActionRequest;
import io.github.jdubois.bootui.core.dto.FlywayActionResult;
import io.github.jdubois.bootui.core.dto.FlywayReport;
import io.github.jdubois.bootui.engine.flyway.FlywayActionResponse;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Flyway schema-migration state and Flyway actions for the {@link Flyway}
 * beans declared in the current application context.
 *
 * <p>A thin HTTP binding over the framework-neutral {@link FlywayService}: the engine owns the report
 * assembly and the {@code migrate}/{@code clean} orchestration, while the Spring-specific bean discovery and
 * Spring-Modulith module-aware behaviour live in {@code SpringFlywayProvider}.</p>
 *
 * <p>Mutating commands require an explicit confirmation payload and remain
 * subject to BootUI's global/per-panel read-only filter.</p>
 */
@RestController
@ConditionalOnClass(Flyway.class)
@RequestMapping("/bootui/api/flyway")
public class FlywayController {

    private final FlywayService flywayService;

    public FlywayController(FlywayService flywayService) {
        this.flywayService = flywayService;
    }

    @GetMapping("/migrations")
    public FlywayReport migrations() {
        return flywayService.report();
    }

    @PostMapping("/migrate")
    public ResponseEntity<FlywayActionResult> migrate(@RequestBody(required = false) FlywayActionRequest request) {
        FlywayActionResponse response = flywayService.migrate(request);
        return ResponseEntity.status(HttpStatus.valueOf(response.status())).body(response.body());
    }

    @PostMapping("/clean")
    public ResponseEntity<FlywayActionResult> clean(@RequestBody(required = false) FlywayActionRequest request) {
        FlywayActionResponse response = flywayService.clean(request);
        return ResponseEntity.status(HttpStatus.valueOf(response.status())).body(response.body());
    }
}
