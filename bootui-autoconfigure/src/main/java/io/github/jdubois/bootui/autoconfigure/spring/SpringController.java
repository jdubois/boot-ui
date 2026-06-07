package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.core.dto.SpringReport;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
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

    private volatile SpringReport lastReport;

    @Autowired
    public SpringController(ApplicationContext applicationContext, Environment environment) {
        this(new SpringScanner(beanFactory(applicationContext), environment, Clock.systemUTC()));
    }

    SpringController(SpringScanner scanner) {
        this.scanner = scanner;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public SpringReport spring() {
        return lastReport;
    }

    @PostMapping("/scan")
    public SpringReport scan() {
        SpringReport report = scanner.scan();
        lastReport = report;
        return report;
    }

    private static ConfigurableListableBeanFactory beanFactory(ApplicationContext applicationContext) {
        if (applicationContext instanceof ConfigurableApplicationContext configurableApplicationContext) {
            return configurableApplicationContext.getBeanFactory();
        }
        return null;
    }
}
