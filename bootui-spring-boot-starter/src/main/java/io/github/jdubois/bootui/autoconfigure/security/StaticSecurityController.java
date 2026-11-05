package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.staticsecurity.StaticSecurityAdvisorService;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Static-only Security Advisor endpoint for Spring applications without Spring Security. */
@RestController
@ConditionalOnMissingClass("org.springframework.security.web.FilterChainProxy")
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/security")
public class StaticSecurityController implements SecurityAdvisorOperations {

    private final StaticSecurityAdvisorService advisor;
    private final DismissedRulesStore dismissedRules;

    @Autowired
    public StaticSecurityController(DismissedRulesStore dismissedRules, BootUiProperties properties) {
        this(
                new StaticSecurityAdvisorService(() -> Path.of(System.getProperty("user.dir", ".")), Clock.systemUTC()),
                dismissedRules,
                properties);
    }

    StaticSecurityController(
            StaticSecurityAdvisorService advisor, DismissedRulesStore dismissedRules, BootUiProperties properties) {
        this.advisor = advisor;
        this.dismissedRules = dismissedRules;
        advisor.setViolationRetentionLimit(() -> properties.getAdvisors().getMaxRetainedViolations());
    }

    @Override
    @GetMapping
    public SecurityReport security() {
        return advisor.applyDismissals(advisor.report(), dismissedRules.load());
    }

    @Override
    @PostMapping("/scan")
    public SecurityReport scan() {
        return advisor.applyDismissals(advisor.scan(), dismissedRules.load());
    }

    @Override
    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return advisor.ruleViolations(ruleId, scanId, offset, limit);
    }
}
