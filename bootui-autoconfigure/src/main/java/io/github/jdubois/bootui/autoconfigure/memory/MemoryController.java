package io.github.jdubois.bootui.autoconfigure.memory;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.ThreadDumpService;
import io.github.jdubois.bootui.core.dto.MemoryReport;
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
@RequestMapping("/bootui/api/memory")
public class MemoryController {

    private final MemoryScanner scanner;

    private volatile MemoryReport lastReport;

    @Autowired
    public MemoryController(BootUiProperties properties) {
        this(new MemoryScanner(
                new MemoryCollector(
                        () -> new ThreadDumpService(properties).report(null, null, 0, 1000),
                        MemoryCollector::diagnosticCommandHistogram),
                Clock.systemUTC()));
    }

    MemoryController(MemoryScanner scanner) {
        this.scanner = scanner;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public MemoryReport memory() {
        return lastReport;
    }

    @PostMapping("/scan")
    public MemoryReport scan() {
        MemoryReport report = scanner.scan();
        lastReport = report;
        return report;
    }
}
