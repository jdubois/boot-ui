package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
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
 * <p>The Hibernate Statistics panel (live {@code SessionFactory} statistics) is a separate
 * Database-group panel served by {@link HibernateStatisticsResource}, not this advisor resource.</p>
 *
 * <p>The resource is produced <em>unconditionally</em> and the engine {@code HibernateScanner} is always
 * wired (it holds no {@code jakarta.persistence} type): when {@code quarkus-hibernate-orm} is absent the
 * scanner's entity-discovery source is unsatisfied, so {@code POST /scan} renders a DISABLED report rather
 * than failing. Availability of the <em>panel</em> in the manifest, by contrast, tracks the
 * {@code HIBERNATE_ORM} capability (see {@code QuarkusPanelAvailability}).</p>
 *
 * <p>The scanner atomically owns the last report and its retained detail index.</p>
 */
@ApplicationScoped
@Path("/bootui/api/hibernate")
public class HibernateResource implements AdvisorViolationsEndpoint {

    private final HibernateScanner scanner;

    private final DismissedRulesStore dismissedRules;

    @Inject
    public HibernateResource(HibernateScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateReport hibernate() {
        return dismissedReport();
    }

    @POST
    @Path("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateReport scan() {
        scanner.scan();
        return dismissedReport();
    }

    @Override
    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return scanner.ruleViolations(
                ruleId, scanId, offset, limit, dismissedRules.snapshot().findings());
    }

    private HibernateReport dismissedReport() {
        DismissedRulesStore.Snapshot dismissed = dismissedRules.snapshot();
        return scanner.dismissedReport(dismissed.rules(), dismissed.findings());
    }
}
