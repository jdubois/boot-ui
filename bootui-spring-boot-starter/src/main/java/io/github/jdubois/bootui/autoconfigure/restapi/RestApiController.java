package io.github.jdubois.bootui.autoconfigure.restapi;

import io.github.jdubois.bootui.autoconfigure.web.AdvisorViolationsEndpoint;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.ErrorContractReport;
import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.errorcontract.ErrorContractService;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the REST API Advisor panel.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} runs the
 * curated REST best-practice ruleset against the host application's controllers and caches the
 * result. {@code GET /error-contract} serves the panel's error-contract catalogue, which is a live,
 * declaration-only read that needs no scan and invokes no exception handler.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/rest-api")
public class RestApiController implements AdvisorViolationsEndpoint {

    private final RestApiScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private final ErrorContractService errorContract;

    public RestApiController(
            RestApiScanner scanner, DismissedRulesStore dismissedRules, ErrorContractService errorContract) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.errorContract = errorContract;
    }

    @GetMapping
    public RestApiReport restApi() {
        return scanner.applyDismissals(scanner.lastReport(), dismissedRules.load());
    }

    /**
     * The declared error contract: which exception handlers exist, what they handle, and what each one is
     * declared to return. Reading it neither invokes a handler nor changes exception resolution.
     */
    @GetMapping("/error-contract")
    public ErrorContractReport errorContract(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return errorContract.report(query, offset, limit);
    }

    @PostMapping("/scan")
    public RestApiReport scan() {
        RestApiReport report = scanner.scan();
        return scanner.applyDismissals(report, dismissedRules.load());
    }

    @Override
    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return scanner.ruleViolations(ruleId, scanId, offset, limit);
    }
}
