package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityController;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityAdvisorService;
import io.github.jdubois.bootui.engine.staticsecurity.StaticSecurityAdvisorService;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.DispatcherHandler;

/** Provides the Security Advisor's static-only route when reactive Spring Security is absent. */
@AutoConfiguration(afterName = "io.github.jdubois.bootui.autoconfigure.BootUiReactiveSpringSecurityAutoConfiguration")
@Conditional(BootUiActivationCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(DispatcherHandler.class)
@ConditionalOnMissingBean(ReactiveSecurityAdvisorService.class)
@Import(ReactiveSecurityController.class)
public class BootUiReactiveStaticSecurityAutoConfiguration {

    @Bean
    ReactiveSecurityAdvisorService bootUiStaticReactiveSecurityAdvisorService(
            DismissedRulesStore dismissedRules, BootUiProperties properties) {
        StaticSecurityAdvisorService staticAdvisor =
                new StaticSecurityAdvisorService(() -> Path.of(System.getProperty("user.dir", ".")), Clock.systemUTC());
        ReactiveSecurityAdvisorService advisor = new ReactiveSecurityAdvisorService(staticAdvisor, dismissedRules);
        advisor.setViolationRetentionLimit(() -> properties.getAdvisors().getMaxRetainedViolations());
        return advisor;
    }
}
