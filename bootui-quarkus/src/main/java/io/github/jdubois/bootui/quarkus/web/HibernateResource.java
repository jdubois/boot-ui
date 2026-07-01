package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Hibernate (ORM mapping) advisor panel ({@code GET /bootui/api/hibernate},
 * {@code POST /bootui/api/hibernate/scan}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code HibernateController}: a thin transport adapter over
 * the shared engine {@link HibernateScanner}, which reads the JPA metamodel and runs a curated registry of
 * static Hibernate best-practice checks against the host application's mapped entities. {@code GET} returns
 * the last report (initially "not scanned"); {@code POST /scan} reads the metamodel and evaluates the rules,
 * caching the result. Dismissed rule IDs from the shared {@link DismissedRulesStore} are applied on read,
 * exactly as on Spring.</p>
 *
 * <p>The resource is produced <em>unconditionally</em> and the engine {@code HibernateScanner} is always
 * wired (it holds no {@code jakarta.persistence} types): when {@code quarkus-hibernate-orm} is absent the
 * scanner's entity-discovery source is unsatisfied, so {@code POST /scan} renders a DISABLED report rather
 * than failing. Availability of the <em>panel</em> in the manifest, by contrast, tracks the
 * {@code HIBERNATE_ORM} capability (see {@code QuarkusPanelAvailability}).</p>
 *
 * <p>It is {@code @ApplicationScoped} (not the default per-request scope) because it caches the last report
 * in a {@code volatile} field across requests — the CDI analogue of the Spring controller's singleton with a
 * {@code volatile lastReport}.</p>
 */
@ApplicationScoped
@Path("/bootui/api/hibernate")
public class HibernateResource {

    private final HibernateScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private volatile HibernateReport lastReport;

    @Inject
    public HibernateResource(HibernateScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateReport hibernate() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @POST
    @Path("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateReport scan() {
        HibernateReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
