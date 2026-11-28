package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Architecture (ArchUnit) hygiene panel ({@code GET /bootui/api/architecture},
 * {@code POST /bootui/api/architecture/scan}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code ArchitectureController}: a thin transport adapter
 * over the shared engine {@link ArchitectureScanner}, which owns the bounded, on-demand ArchUnit import
 * (bounded to the application base packages discovered from the build-time Jandex index via
 * {@code QuarkusBasePackageProvider}) and the curated rule registry. {@code GET} returns the last report
 * (initially "not scanned"); {@code POST /scan} runs the rules against the host application classes and
 * caches the result. Dismissed rule IDs from the shared {@link DismissedRulesStore} are applied on read,
 * exactly as on Spring.</p>
 *
 * <p>The scanner atomically owns the last report and its retained detail index.</p>
 */
@ApplicationScoped
@Path("/bootui/api/architecture")
public class ArchitectureResource implements AdvisorViolationsEndpoint {

    private final ArchitectureScanner scanner;

    private final DismissedRulesStore dismissedRules;

    @Inject
    public ArchitectureResource(ArchitectureScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ArchitectureReport architecture() {
        return scanner.applyDismissals(scanner.lastReport(), dismissedRules.load());
    }

    @POST
    @Path("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public ArchitectureReport scan() {
        ArchitectureReport report = scanner.scan();
        return scanner.applyDismissals(report, dismissedRules.load());
    }

    @Override
    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return scanner.ruleViolations(ruleId, scanId, offset, limit);
    }
}
