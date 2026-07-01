package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyReports;
import io.github.jdubois.bootui.quarkus.OsvVulnerabilityScanner;
import io.github.jdubois.bootui.quarkus.QuarkusDependencyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.config.Config;

/**
 * JAX-RS resource for the Vulnerabilities panel ({@code GET /bootui/api/vulnerabilities},
 * {@code POST /bootui/api/vulnerabilities/scan}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code VulnerabilitiesController}: a thin transport
 * adapter that lists the local dependency inventory (from the build-time-captured
 * {@link QuarkusDependencyProvider}) and, only on the user-initiated {@code POST /scan}, enriches it via the
 * {@link OsvVulnerabilityScanner}. The engine {@link DependencyReports} owns all aggregation/ordering. Both
 * collaborators are injected as their concrete adapter types (not the {@code DependencyProvider} /
 * {@code VulnerabilityScanner} SPI interfaces) so adding further SPI impls later can never make this wiring
 * ambiguous. Per-vulnerability dismissals from the shared {@link DismissedRulesStore} (keyed by
 * {@link DependencyReports#dismissalKey(String, String)}) are applied on every response, exactly as on
 * Spring.</p>
 *
 * <p><strong>Never scans on render.</strong> {@code GET} returns the cached last scan report if present,
 * otherwise a {@code NOT_SCANNED} inventory — it makes no network call. {@code POST /scan} honors
 * {@code bootui.vulnerabilities.osv-enabled} (read live from {@code Config}): when disabled it returns a
 * {@code DISABLED} report <em>without</em> caching it, exactly as the Spring controller does. The shared
 * engine {@code LocalhostGuard} (the Vert.x safety filter) already enforces the local-only write floor on
 * the {@code POST}.</p>
 *
 * <p>It is {@code @ApplicationScoped} because it caches the last report in a {@code volatile} field across
 * requests — the CDI analogue of the Spring controller's singleton with a {@code volatile lastScanReport}.</p>
 */
@ApplicationScoped
@Path("/bootui/api/vulnerabilities")
public class VulnerabilitiesResource {

    private final QuarkusDependencyProvider dependencyProvider;

    private final OsvVulnerabilityScanner vulnerabilityScanner;

    private final Config config;

    private final DismissedRulesStore dismissedRules;

    private volatile DependenciesReport lastScanReport;

    @Inject
    public VulnerabilitiesResource(
            QuarkusDependencyProvider dependencyProvider,
            OsvVulnerabilityScanner vulnerabilityScanner,
            Config config,
            DismissedRulesStore dismissedRules) {
        this.dependencyProvider = dependencyProvider;
        this.vulnerabilityScanner = vulnerabilityScanner;
        this.config = config;
        this.dismissedRules = dismissedRules;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DependenciesReport dependencies() {
        DependenciesReport cached = this.lastScanReport;
        if (cached != null) {
            return DependencyReports.applyDismissals(cached, dismissedRules.load());
        }
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        DependenciesReport report = DependencyReports.report(
                osvEnabled(),
                "NOT_SCANNED",
                "Dependency inventory loaded. Click Scan with OSV.dev to check for known vulnerabilities.",
                null,
                0,
                dependencies);
        return DependencyReports.applyDismissals(report, dismissedRules.load());
    }

    @POST
    @Path("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public DependenciesReport scan() {
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        DependenciesReport report;
        if (!osvEnabled()) {
            report = DependencyReports.report(
                    false,
                    "DISABLED",
                    "OSV scanning is disabled. Set bootui.vulnerabilities.osv-enabled=true to allow on-demand scans.",
                    null,
                    0,
                    dependencies);
        } else {
            report = vulnerabilityScanner.scan(dependencies);
        }
        if (!"DISABLED".equals(report.status())) {
            this.lastScanReport = report;
        }
        return DependencyReports.applyDismissals(report, dismissedRules.load());
    }

    private boolean osvEnabled() {
        return config.getOptionalValue("bootui.vulnerabilities.osv-enabled", Boolean.class)
                .orElse(Boolean.TRUE);
    }
}
