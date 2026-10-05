package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** Shared read-only route with strict query parsing for both Spring request stacks. */
public interface AdvisorViolationsEndpoint {

    @GetMapping("/rules/{ruleId}/violations")
    default AdvisorRuleViolationsDto violations(
            @PathVariable("ruleId") String ruleId,
            @RequestParam(name = "scanId", required = false) String scanId,
            @RequestParam(name = "offset", required = false) String offset,
            @RequestParam(name = "limit", required = false) String limit) {
        return ruleViolations(
                ruleId,
                scanId,
                AdvisorViolationException.parseInteger(offset, "offset"),
                AdvisorViolationException.parseInteger(limit, "limit"));
    }

    AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit);
}
