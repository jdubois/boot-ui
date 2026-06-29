package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.quarkusapp.QuarkusAppScanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Quarkus-native application advisor ({@code GET /bootui/api/spring},
 * {@code POST /bootui/api/spring/scan}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code SpringController}: a thin transport adapter over the
 * shared engine {@link QuarkusAppScanner}, which evaluates a Quarkus-native ruleset (CDI scopes, MicroProfile
 * config, reactive idioms, profiles) against build-time idiom counts. Shares the {@link SpringReport} DTO and
 * panel with Spring; the rules differ. {@code GET} returns the last report (initially "not scanned");
 * {@code POST /scan} re-evaluates and caches. Dismissed rule IDs are applied on read, exactly as on Spring.</p>
 */
@ApplicationScoped
@Path("/bootui/api/spring")
public class SpringResource {

    private final QuarkusAppScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private volatile SpringReport lastReport;

    @Inject
    public SpringResource(QuarkusAppScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public SpringReport spring() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @POST
    @Path("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public SpringReport scan() {
        SpringReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
