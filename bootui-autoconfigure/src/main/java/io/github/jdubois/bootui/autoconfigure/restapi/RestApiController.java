package io.github.jdubois.bootui.autoconfigure.restapi;

import io.github.jdubois.bootui.core.dto.RestApiReport;
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
@RequestMapping("/bootui/api/rest-api")
public class RestApiController {

    private final RestApiScanner scanner;

    private volatile RestApiReport lastReport;

    @Autowired
    public RestApiController(ApplicationContext applicationContext) {
        this(new RestApiScanner(
                () -> RestApiPackages.detect(applicationContext),
                new ClassFileRestApiImporter(),
                () -> ClassUtils.isPresent(
                        "io.swagger.v3.oas.annotations.Operation", RestApiController.class.getClassLoader()),
                Clock.systemUTC()));
    }

    RestApiController(RestApiScanner scanner) {
        this.scanner = scanner;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public RestApiReport restApi() {
        return lastReport;
    }

    @PostMapping("/scan")
    public RestApiReport scan() {
        RestApiReport report = scanner.scan();
        lastReport = report;
        return report;
    }
}
