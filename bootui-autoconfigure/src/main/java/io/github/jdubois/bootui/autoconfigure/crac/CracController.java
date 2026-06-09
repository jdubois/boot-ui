package io.github.jdubois.bootui.autoconfigure.crac;

import io.github.jdubois.bootui.autoconfigure.crac.CracReadinessScanner.CracScanResult;
import io.github.jdubois.bootui.core.dto.CracReadinessReport;
import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the local CRaC (Coordinated Restore at Checkpoint) panel.
 *
 * <p>{@code GET} returns the live runtime status plus the last readiness report (initially "not
 * scanned"); {@code POST /scan} runs the curated readiness checks and caches the result. The runtime
 * status is recomputed on every request so it always reflects the current process, while the
 * heuristic scan is only refreshed by the explicit action.</p>
 */
@RestController
@RequestMapping("/bootui/api/crac")
public class CracController {

    private final CracReadinessScanner scanner;
    private final CracRuntimeStatusCollector runtimeStatusCollector;

    private volatile CracScanResult lastResult;

    @Autowired
    public CracController(ApplicationContext applicationContext, Environment environment) {
        this(
                new CracReadinessScanner(
                        () -> CracPackages.detect(applicationContext), new ClassFileCracImporter(), Clock.systemUTC()),
                new CracRuntimeStatusCollector(environment));
    }

    CracController(CracReadinessScanner scanner, CracRuntimeStatusCollector runtimeStatusCollector) {
        this.scanner = scanner;
        this.runtimeStatusCollector = runtimeStatusCollector;
        this.lastResult = scanner.initialResult();
    }

    @GetMapping
    public CracReadinessReport crac() {
        return scanner.report(lastResult, runtimeStatus());
    }

    @PostMapping("/scan")
    public CracReadinessReport scan() {
        CracScanResult result = scanner.scan();
        lastResult = result;
        return scanner.report(result, runtimeStatus());
    }

    private CracRuntimeStatusDto runtimeStatus() {
        return runtimeStatusCollector.collect();
    }
}
