package io.github.jdubois.bootui.engine.reactivesecurity;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.staticsecurity.StaticSecurityAdvisorService;
import java.util.function.IntSupplier;

/** Shared stateful facade used by the WebFlux HTTP and MCP adapters. */
public final class ReactiveSecurityAdvisorService {

    private final ReactiveSecurityScanner scanner;
    private final StaticSecurityAdvisorService staticOnlyAdvisor;
    private final DismissedRulesStore dismissedRules;

    public ReactiveSecurityAdvisorService(ReactiveSecurityScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.staticOnlyAdvisor = null;
        this.dismissedRules = dismissedRules;
    }

    public ReactiveSecurityAdvisorService(
            StaticSecurityAdvisorService staticOnlyAdvisor, DismissedRulesStore dismissedRules) {
        this.scanner = null;
        this.staticOnlyAdvisor = staticOnlyAdvisor;
        this.dismissedRules = dismissedRules;
    }

    public SecurityReport report() {
        return lastReport();
    }

    public SecurityReport lastReport() {
        if (staticOnlyAdvisor != null) {
            return staticOnlyAdvisor.applyDismissals(staticOnlyAdvisor.report(), dismissedRules.load());
        }
        return scanner.applyDismissals(scanner.lastReport(), dismissedRules.load());
    }

    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        if (staticOnlyAdvisor != null) {
            return staticOnlyAdvisor.ruleViolations(ruleId, scanId, offset, limit);
        }
        return scanner.ruleViolations(ruleId, scanId, offset, limit);
    }

    public void setViolationRetentionLimit(IntSupplier limit) {
        if (staticOnlyAdvisor != null) {
            staticOnlyAdvisor.setViolationRetentionLimit(limit);
            return;
        }
        scanner.setViolationRetentionLimit(limit);
    }

    public SecurityReport scan() {
        if (staticOnlyAdvisor != null) {
            return staticOnlyAdvisor.applyDismissals(staticOnlyAdvisor.scan(), dismissedRules.load());
        }
        SecurityReport report = scanner.scan();
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
