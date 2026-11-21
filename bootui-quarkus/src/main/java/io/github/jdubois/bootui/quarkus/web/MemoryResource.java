package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Map;

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
 * <p>The scanner atomically owns the last report and its retained detail index.
 * {@code POST /scan} is {@code @Blocking}:
 * a requested heap-content histogram requests GC and may pause the JVM, so it must not run on the Vert.x event loop.
 * The mutating scan is gated by the shared {@code LocalhostGuard} write floor enforced by
 * {@code BootUiQuarkusSafetyFilter}.</p>
 */
@ApplicationScoped
@Path("/bootui/api/memory")
public class MemoryResource implements AdvisorViolationsEndpoint {

    private final MemoryScanner scanner;

    private final DismissedRulesStore dismissedRules;

    @Inject
    public MemoryResource(MemoryScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public MemoryReport memory() {
        return scanner.applyDismissals(scanner.lastReport(), dismissedRules.load());
    }

    public MemoryReport scan() {
        MemoryReport report = scanner.scan();
        return scanner.applyDismissals(report, dismissedRules.load());
    }

    @POST
    @Path("/scan")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public MemoryReport scan(@Context UriInfo uriInfo) {
        Boolean selected;
        try {
            selected = MemoryScanner.parseIncludeHistogram(uriInfo.getQueryParameters());
        } catch (IllegalArgumentException exception) {
            throw new WebApplicationException(Response.status(400)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("message", exception.getMessage()))
                    .build());
        }
        if (selected == null) return scan();
        return scanner.applyDismissals(scanner.scan(selected), dismissedRules.load());
    }

    @Override
    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return scanner.ruleViolations(ruleId, scanId, offset, limit);
    }
}
