package io.github.jdubois.bootui.engine.reactivesecurity;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;

/** Shared stateful facade used by the WebFlux HTTP and MCP adapters. */
public final class ReactiveSecurityAdvisorService {

    private final ReactiveSecurityScanner scanner;
    private final DismissedRulesStore dismissedRules;
    private volatile SecurityReport lastReport;

    public ReactiveSecurityAdvisorService(ReactiveSecurityScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    public SecurityReport report() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    public SecurityReport scan() {
        SecurityReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
