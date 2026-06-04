package io.github.jdubois.bootui.autoconfigure.securityadvisor;

import io.github.jdubois.bootui.core.dto.SecurityAdvisorReport;
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
@RequestMapping("/bootui/api/security-advisor")
public class SecurityAdvisorController {

    private final SecurityAdvisorScanner scanner;

    private volatile SecurityAdvisorReport lastReport;

    @Autowired
    public SecurityAdvisorController(
            ObjectProvider<FilterChainProxy> filterChainProxies,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment) {
        this(new SecurityAdvisorScanner(filterChainProxies, beanFactories, environment, Clock.systemUTC()));
    }

    SecurityAdvisorController(SecurityAdvisorScanner scanner) {
        this.scanner = scanner;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public SecurityAdvisorReport securityAdvisor() {
        return lastReport;
    }

    @PostMapping("/scan")
    public SecurityAdvisorReport scan() {
        SecurityAdvisorReport report = scanner.scan();
        lastReport = report;
        return report;
    }
}
