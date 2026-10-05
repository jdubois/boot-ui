package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.journal.BootUiApplicationEventMulticaster;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;

/**
 * Installs BootUI's application event multicaster ({@code docs/PLAN-v2.md} §5.18, M4-8), which records application
 * events and their listeners' runs in the runtime journal, unless the application or another auto-configuration defines
 * its own.
 *
 * <p>The bean has the context's reserved name, {@code applicationEventMulticaster}, and an application cannot have two:
 * whichever is registered first keeps it, and a later definition without a condition, such as Spring Modulith's event
 * publication registry's, fails the application's start rather than override it. So BootUI registers its own last, in
 * an auto-configuration of its own, processed after Spring Modulith's, named so that an application without it loads
 * none of its classes, and at the lowest precedence, after every other auto-configuration that could define the name,
 * and backs off when one did. {@code SpringAppEventCapture} then reports that no application event is recorded.</p>
 *
 * <p>It activates exactly when {@link BootUiAutoConfiguration} or {@link BootUiReactiveAutoConfiguration} does, so it
 * is absent whenever BootUI is off.</p>
 */
@AutoConfiguration(afterName = BootUiApplicationEventMulticasterAutoConfiguration.MODULITH_EVENT_PUBLICATION)
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@Conditional({
    BootUiActivationCondition.class,
    BootUiApplicationEventMulticasterAutoConfiguration.BootUiWebStackCondition.class
})
public class BootUiApplicationEventMulticasterAutoConfiguration {

    /** Spring Modulith's auto-configuration defining {@code applicationEventMulticaster} for its publication registry. */
    static final String MODULITH_EVENT_PUBLICATION =
            "org.springframework.modulith.events.config.EventPublicationAutoConfiguration";

    /**
     * The context's event multicaster, unless one is already defined in this context: a parent context's multicaster,
     * which every refreshed context has, is not this context's. The context creates it before other beans, so its
     * method is static and reads the journal only when an event is published.
     */
    @Bean(name = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME)
    @ConditionalOnMissingBean(
            name = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
            search = SearchStrategy.CURRENT)
    static BootUiApplicationEventMulticaster bootUiApplicationEventMulticaster(
            BeanFactory beanFactory, ObjectProvider<RuntimeJournal> journal) {
        return new BootUiApplicationEventMulticaster(beanFactory, journal::getIfAvailable);
    }

    /** The web stacks BootUI serves, as its servlet and reactive auto-configurations require them. */
    static final class BootUiWebStackCondition extends AnyNestedCondition {

        BootUiWebStackCondition() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
        static final class Servlet {}

        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
        @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
        static final class Reactive {}
    }
}
