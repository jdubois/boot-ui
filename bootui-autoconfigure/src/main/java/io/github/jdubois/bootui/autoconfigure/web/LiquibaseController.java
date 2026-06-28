package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.LiquibaseActionRequest;
import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import io.github.jdubois.bootui.core.dto.LiquibaseReport;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseActionResponse;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseService;
import jakarta.annotation.Nullable;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Liquibase change-log history and update actions for the {@link SpringLiquibase} beans declared in the
 * current application context.
 *
 * <p>This is a thin binding over the framework-neutral engine {@link LiquibaseService}: the controller maps the
 * request/response transport while the service owns the change-log assembly and update orchestration, and the
 * {@code SpringLiquibaseProvider} owns the {@code liquibase.*}-typed discovery and the update primitive.</p>
 *
 * <p>Mutating commands require an explicit confirmation payload and remain subject to BootUI's global/per-panel
 * read-only filter.</p>
 */
@RestController
@ConditionalOnClass(SpringLiquibase.class)
@RequestMapping("/bootui/api/liquibase")
public class LiquibaseController {

    private final LiquibaseService liquibaseService;

    public LiquibaseController(LiquibaseService liquibaseService) {
        this.liquibaseService = liquibaseService;
    }

    @GetMapping("/changesets")
    public LiquibaseReport changeSets() {
        return liquibaseService.report();
    }

    @PostMapping("/update")
    public ResponseEntity<LiquibaseActionResult> update(
            @RequestBody(required = false) @Nullable LiquibaseActionRequest request) {
        LiquibaseActionResponse response = liquibaseService.update(request);
        return ResponseEntity.status(HttpStatus.valueOf(response.status())).body(response.body());
    }
}
