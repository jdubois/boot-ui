package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import java.util.List;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;

/**
 * Owns one {@code SpringApplication} run's claim on the BootUI Java agent ({@code docs/PLAN-v2.md} D34). The
 * {@link BootUiAgentClaimEnvironmentPostProcessor} creates one per run and registers it as that application's
 * initializer and listener; nothing static holds it, so a DevTools restart's discarded run takes its claim with it.
 *
 * <p>As initializer, it records its context and registers itself as a singleton so the Java Agent panel can read the
 * claim. As listener, it refines the claim with the auto-configuration packages when its own context is refreshed, and
 * attaches the context's {@link AgentHandoffs} so the agent starts propagating requests' context, and disarms it when its own context closes or the application fails to start. It also disarms when the context destroys
 * its singletons. Disarming is idempotent.
 */
public final class AgentClaimOwner
        implements ApplicationContextInitializer<ConfigurableApplicationContext>,
                SmartApplicationListener,
                DisposableBean,
                Ordered {

    /** The singleton name of the run's owner in its context. */
    public static final String BEAN_NAME = "bootUiAgentClaimOwner";

    private final AgentClaim claim;
    private volatile ConfigurableApplicationContext context;

    AgentClaimOwner(AgentClaim claim) {
        this.claim = claim;
    }

    /** This run's claim. */
    public AgentClaim claim() {
        return claim;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** Only the three lifecycle events: the multicaster then never calls this listener for any other event. */
    @Override
    public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return ContextRefreshedEvent.class.isAssignableFrom(eventType)
                || ContextClosedEvent.class.isAssignableFrom(eventType)
                || ApplicationFailedEvent.class.isAssignableFrom(eventType);
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        if (context != null) {
            return;
        }
        context = applicationContext;
        try {
            ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
            if (!beanFactory.containsSingleton(BEAN_NAME)) {
                beanFactory.registerSingleton(BEAN_NAME, this);
                if (beanFactory instanceof DefaultSingletonBeanRegistry registry) {
                    registry.registerDisposableBean(BEAN_NAME, this);
                }
            }
        } catch (RuntimeException ex) {
            // A context whose bean factory is not ready yet: the panel cannot show the claim, the claim still works.
        }
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        try {
            if (event instanceof ContextRefreshedEvent refreshed) {
                if (context != null && refreshed.getApplicationContext() == context) {
                    refine(context.getBeanFactory());
                    attach(context);
                }
            } else if (event instanceof ContextClosedEvent closed) {
                if (context != null && closed.getApplicationContext() == context) {
                    claim.disarm();
                }
            } else if (event instanceof ApplicationFailedEvent failed) {
                // A child context's failure reaches this listener too: only this run's own failure disarms.
                if (failed.getApplicationContext() == null || failed.getApplicationContext() == context) {
                    claim.disarm();
                }
            }
        } catch (RuntimeException ex) {
            // The agent's bookkeeping never breaks the application's lifecycle.
        }
    }

    @Override
    public void destroy() {
        claim.disarm();
    }

    /**
     * Hands the claim this run's engine side of executor propagation, now that the engine is ready, and starts Code
     * Inventory for the run.
     */
    private void attach(ConfigurableApplicationContext applicationContext) {
        if (!claim.armed()) {
            return;
        }
        AgentHandoffs handoffs =
                applicationContext.getBeanProvider(AgentHandoffs.class).getIfUnique();
        if (handoffs != null) {
            claim.attach(handoffs);
        }
        // Code Inventory's drainer and scan of this run's class files (PLAN-v2 §5.15); the bean stops them at close.
        CodeInventoryService inventory =
                applicationContext.getBeanProvider(CodeInventoryService.class).getIfUnique();
        if (inventory != null) {
            inventory.start();
        }
    }

    private void refine(ConfigurableListableBeanFactory beanFactory) {
        if (!claim.armed()) {
            return;
        }
        List<String> packages =
                AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory) : List.of();
        claim.refine(packages);
    }
}
