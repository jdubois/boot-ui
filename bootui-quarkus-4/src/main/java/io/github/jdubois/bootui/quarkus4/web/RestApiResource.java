package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.ErrorContractReport;
import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.errorcontract.ErrorContractService;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the REST API advisor panel ({@code GET /bootui/api/rest-api},
 * {@code POST /bootui/api/rest-api/scan}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code RestApiController}: a thin transport adapter over the
 * shared engine {@link RestApiScanner}, which owns the bounded, on-demand ArchUnit import (bounded to the
 * application base packages discovered from the build-time Jandex index via {@code QuarkusBasePackageProvider})
 * and the curated REST best-practice ruleset. The engine models JAX-RS resources alongside Spring controllers,
 * so the same rules light up on Quarkus. {@code GET} returns the last report (initially "not scanned");
 * {@code POST /scan} runs the rules and caches the result. Dismissed rule IDs from the shared
 * {@link DismissedRulesStore} are applied on read, exactly as on Spring.</p>
 *
 * <p>The scanner atomically owns the last report and its retained detail index.</p>
 */
@ApplicationScoped
@Path("/bootui/api/rest-api")
public class RestApiResource implements AdvisorViolationsEndpoint {

    private final RestApiScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private final ErrorContractService errorContract;

    @Inject
    public RestApiResource(
            RestApiScanner scanner, DismissedRulesStore dismissedRules, ErrorContractService errorContract) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.errorContract = errorContract;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RestApiReport restApi() {
        return scanner.applyDismissals(scanner.lastReport(), dismissedRules.load());
    }

    /**
     * The declared error contract ({@code GET /bootui/api/rest-api/error-contract}), the Quarkus analogue of
     * the Spring adapter's {@code RestApiController#errorContract}. It reads the build-time-captured
     * exception-mapper declarations: no mapper is instantiated or invoked, and exception resolution is
     * unchanged.
     */
    @GET
    @Path("/error-contract")
    @Produces(MediaType.APPLICATION_JSON)
    public ErrorContractReport errorContract(
            @QueryParam("q") String query, @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
        return errorContract.report(query, offset, limit);
    }

    @POST
    @Path("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public RestApiReport scan() {
        RestApiReport report = scanner.scan();
        return scanner.applyDismissals(report, dismissedRules.load());
    }

    @Override
    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return scanner.ruleViolations(ruleId, scanId, offset, limit);
    }
}
