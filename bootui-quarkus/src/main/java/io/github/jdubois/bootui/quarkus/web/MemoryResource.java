package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.memory.MemoryScanner;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Memory advisor panel ({@code GET /bootui/api/memory},
 * {@code POST /bootui/api/memory/scan}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code MemoryController}: a thin transport adapter
 * over the shared engine {@link MemoryScanner}, which owns the framework-neutral JVM data collection
 * (heap/GC/threads/class-loading/heap-content) and the curated static health ruleset. {@code GET}
 * returns the last report (initially "not scanned"); {@code POST /scan} runs the rules against the
 * live management beans and caches the result. Dismissed rule IDs from the shared
 * {@link DismissedRulesStore} are applied on read, exactly as on Spring. The advisor is always
 * available because it relies only on JMX beans present on every JVM.</p>
 *
 * <p>The resource is {@code @ApplicationScoped} (not the default per-request scope) because it caches
 * the last report in a {@code volatile} field across requests — the CDI analogue of the Spring
 * controller's singleton with a {@code volatile lastReport}. {@code POST /scan} is {@code @Blocking}:
 * collecting the heap-content histogram forces a full GC, which must not run on the Vert.x event loop.
 * The mutating scan is gated by the shared {@code LocalhostGuard} write floor enforced by
 * {@code BootUiQuarkusSafetyFilter}.</p>
 */
@ApplicationScoped
@Path("/bootui/api/memory")
public class MemoryResource {

    private final MemoryScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private volatile MemoryReport lastReport;

    @Inject
    public MemoryResource(MemoryScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public MemoryReport memory() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @POST
    @Path("/scan")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public MemoryReport scan() {
        MemoryReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
