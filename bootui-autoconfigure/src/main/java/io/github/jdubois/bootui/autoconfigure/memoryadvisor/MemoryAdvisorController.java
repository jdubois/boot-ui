package io.github.jdubois.bootui.autoconfigure.memoryadvisor;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.ThreadDumpService;
import io.github.jdubois.bootui.core.dto.MemoryAdvisorReport;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Memory Advisor panel.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} aggregates the
 * JVM memory, thread, and heap-content data already produced by the Memory, Threads, and Heap Dump
 * panels and evaluates a bounded, static health ruleset against it. The advisor is always available
 * because it relies only on JMX management beans that are present on every JVM.</p>
 */
@RestController
@RequestMapping("/bootui/api/memory-advisor")
public class MemoryAdvisorController {

    private final MemoryAdvisorScanner scanner;

    private volatile MemoryAdvisorReport lastReport;

    @Autowired
    public MemoryAdvisorController(BootUiProperties properties) {
        this(new MemoryAdvisorScanner(
                new MemoryAdvisorCollector(
                        () -> new ThreadDumpService(properties).report(null, null, 0, 1000),
                        MemoryAdvisorCollector::diagnosticCommandHistogram),
                Clock.systemUTC()));
    }

    MemoryAdvisorController(MemoryAdvisorScanner scanner) {
        this.scanner = scanner;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public MemoryAdvisorReport memoryAdvisor() {
        return lastReport;
    }

    @PostMapping("/scan")
    public MemoryAdvisorReport scan() {
        MemoryAdvisorReport report = scanner.scan();
        lastReport = report;
        return report;
    }
}
