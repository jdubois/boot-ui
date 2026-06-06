package io.github.jdubois.bootui.autoconfigure.restadvisor;

import io.github.jdubois.bootui.core.dto.RestApiAdvisorReport;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the REST API Advisor panel.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} runs the
 * curated REST best-practice ruleset against the host application's controllers and caches the
 * result.</p>
 */
@RestController
@RequestMapping("/bootui/api/rest-advisor")
public class RestApiAdvisorController {

    private final RestApiAdvisorScanner scanner;

    private volatile RestApiAdvisorReport lastReport;

    @Autowired
    public RestApiAdvisorController(ApplicationContext applicationContext) {
        this(new RestApiAdvisorScanner(
                () -> RestApiAdvisorPackages.detect(applicationContext),
                new ClassFileRestApiAdvisorImporter(),
                () -> ClassUtils.isPresent(
                        "io.swagger.v3.oas.annotations.Operation", RestApiAdvisorController.class.getClassLoader()),
                Clock.systemUTC()));
    }

    RestApiAdvisorController(RestApiAdvisorScanner scanner) {
        this.scanner = scanner;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public RestApiAdvisorReport restAdvisor() {
        return lastReport;
    }

    @PostMapping("/scan")
    public RestApiAdvisorReport scan() {
        RestApiAdvisorReport report = scanner.scan();
        lastReport = report;
        return report;
    }
}
