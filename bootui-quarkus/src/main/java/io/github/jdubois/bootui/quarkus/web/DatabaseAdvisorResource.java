package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Database Advisor panel ({@code GET /bootui/api/database-advisor},
 * {@code POST /bootui/api/database-advisor/scan}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code DatabaseAdvisorController}: a thin transport adapter
 * over the shared engine {@link DatabaseAdvisorScanner}, which introspects the physical schema of every
 * discovered {@code DataSource} through plain JDBC {@code DatabaseMetaData} and, when a Hibernate metamodel is
 * also available, cross-references it. {@code GET} returns the last report (initially "not scanned");
 * {@code POST /scan} runs the scan and caches the result. Dismissed rule IDs from the shared
 * {@link DismissedRulesStore} are applied on read, exactly as on Spring.</p>
 *
 * <p>The resource is produced <em>unconditionally</em> and the engine {@code DatabaseAdvisorScanner} is always
 * wired (it holds no {@code io.agroal}/{@code jakarta.persistence} types): when no {@code DataSource} bean is
 * present the scan renders a DISABLED report rather than failing.</p>
 *
 * <p>The scanner atomically owns the last report and its retained detail index.
 * {@code POST /scan} is {@code @Blocking}: it opens JDBC connections and runs
 * several {@code DatabaseMetaData}/catalog queries per datasource, which must not run on the Vert.x event
 * loop.</p>
 */
@ApplicationScoped
@Path("/bootui/api/database-advisor")
public class DatabaseAdvisorResource implements AdvisorViolationsEndpoint {

    private final DatabaseAdvisorScanner scanner;

    private final DismissedRulesStore dismissedRules;

    @Inject
    public DatabaseAdvisorResource(DatabaseAdvisorScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DatabaseAdvisorReport databaseAdvisor() {
        return dismissedReport();
    }

    @POST
    @Path("/scan")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public DatabaseAdvisorReport scan() {
        scanner.scan();
        return dismissedReport();
    }

    @Override
    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return scanner.ruleViolations(
                ruleId, scanId, offset, limit, dismissedRules.snapshot().findings());
    }

    private DatabaseAdvisorReport dismissedReport() {
        DismissedRulesStore.Snapshot dismissed = dismissedRules.snapshot();
        return scanner.dismissedReport(dismissed.rules(), dismissed.findings());
    }
}
