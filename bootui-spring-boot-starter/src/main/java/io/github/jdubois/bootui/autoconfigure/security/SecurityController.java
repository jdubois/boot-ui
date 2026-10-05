package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.AdvisorViolationsEndpoint;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import java.time.Clock;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.env.Environment;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Spring Security Advisor panel.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} introspects the
 * registered {@code SecurityFilterChain} beans and related security beans and evaluates a bounded,
 * static best-practice ruleset against the host application's configuration.</p>
 */
@RestController
@ConditionalOnClass(FilterChainProxy.class)
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/security")
public class SecurityController implements AdvisorViolationsEndpoint {

    private final SecurityScanner scanner;

    private final DismissedRulesStore dismissedRules;

    @Autowired
    public SecurityController(
            ObjectProvider<FilterChainProxy> filterChainProxies,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment,
            DismissedRulesStore dismissedRules,
            BootUiProperties properties) {
        this(new SecurityScanner(filterChainProxies, beanFactories, environment, Clock.systemUTC()), dismissedRules);
        scanner.setViolationRetentionLimit(() -> properties.getAdvisors().getMaxRetainedViolations());
    }

    SecurityController(SecurityScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
    }

    @GetMapping
    public SecurityReport security() {
        return scanner.applyDismissals(scanner.lastReport(), dismissedRules.load());
    }

    @PostMapping("/scan")
    public SecurityReport scan() {
        SecurityReport report = scanner.scan();
        return scanner.applyDismissals(report, dismissedRules.load());
    }

    @Override
    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return scanner.ruleViolations(ruleId, scanId, offset, limit);
    }
}
