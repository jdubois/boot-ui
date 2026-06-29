package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.quarkussecurity.QuarkusSecurityScanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Quarkus-native Security advisor ({@code GET /bootui/api/security},
 * {@code POST /bootui/api/security/scan}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code SecurityController}: a thin transport adapter over
 * the shared engine {@link QuarkusSecurityScanner}, which evaluates a Quarkus-native ruleset against the
 * effective {@code quarkus.*} security config + build-time authorization annotation counts. Shares the
 * {@link SecurityReport} DTO and panel with Spring; the rules differ (Elytron/OIDC, not Spring Security).
 * {@code GET} returns the last report (initially "not scanned"); {@code POST /scan} re-evaluates and caches.
 * Dismissed rule IDs are applied on read, exactly as on Spring.</p>
 */
@ApplicationScoped
@Path("/bootui/api/security")
public class SecurityResource {

    private final QuarkusSecurityScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private volatile SecurityReport lastReport;

    @Inject
    public SecurityResource(QuarkusSecurityScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public SecurityReport security() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @POST
    @Path("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public SecurityReport scan() {
        SecurityReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
