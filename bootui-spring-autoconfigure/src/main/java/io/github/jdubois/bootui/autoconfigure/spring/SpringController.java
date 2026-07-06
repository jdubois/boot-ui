package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Spring Advisor panel.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} takes a
 * read-only snapshot of the running application context and evaluates a bounded best-practice
 * ruleset against it.</p>
 */
@RestController
@RequestMapping("/bootui/api/spring")
public class SpringController {

    private final SpringScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private volatile SpringReport lastReport;

    @Autowired
    public SpringController(
            ApplicationContext applicationContext, Environment environment, DismissedRulesStore dismissedRules) {
        this(
                new SpringScanner(
                        beanFactory(applicationContext),
                        environment,
                        isReactive(applicationContext),
                        Clock.systemUTC()),
                dismissedRules);
    }

    SpringController(SpringScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    // This same controller class is imported unmodified by both BootUiAutoConfiguration (servlet) and
    // BootUiReactiveAutoConfiguration (WebFlux) - see PanelsController.isReactive() for the same pattern.
    // ReactiveWebApplicationContext is the deterministic Spring Boot marker for "this is the reactive
    // stack" (set by the actual running ApplicationContext type, not a classpath heuristic), so it
    // correctly distinguishes the two adapters even if both spring-webmvc and spring-webflux happen to
    // be on the classpath at once.
    private static boolean isReactive(ApplicationContext applicationContext) {
        return applicationContext instanceof ReactiveWebApplicationContext;
    }

    @GetMapping
    public SpringReport spring() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @PostMapping("/scan")
    public SpringReport scan() {
        SpringReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }

    private static ConfigurableListableBeanFactory beanFactory(ApplicationContext applicationContext) {
        if (applicationContext instanceof ConfigurableApplicationContext configurableApplicationContext) {
            return configurableApplicationContext.getBeanFactory();
        }
        return null;
    }
}
