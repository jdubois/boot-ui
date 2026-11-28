package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** Shared read-only route with the same strict query parsing as the Spring adapters. */
public interface AdvisorViolationsEndpoint {

    @GET
    @Path("/rules/{ruleId}/violations")
    @Produces(MediaType.APPLICATION_JSON)
    default AdvisorRuleViolationsDto violations(
            @PathParam("ruleId") String ruleId,
            @QueryParam("scanId") String scanId,
            @QueryParam("offset") String offset,
            @QueryParam("limit") String limit) {
        return ruleViolations(
                ruleId,
                scanId,
                AdvisorViolationException.parseInteger(offset, "offset"),
                AdvisorViolationException.parseInteger(limit, "limit"));
    }

    AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit);
}
