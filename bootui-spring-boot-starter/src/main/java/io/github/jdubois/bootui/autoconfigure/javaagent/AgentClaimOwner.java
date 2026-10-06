package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.engine.codepaths.CodePathsService;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.javaagent.AgentCaughtExceptions;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.sideeffects.SideEffectsService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/**
 * Owns one {@code SpringApplication} run's claim on the BootUI Java agent ({@code docs/PLAN-v2.md} D34). The
 * {@link BootUiAgentClaimEnvironmentPostProcessor} creates one per run and registers it as that application's
 * initializer and listener; nothing static holds it, so a DevTools restart's discarded run takes its claim with it.
 *
 * <p>As initializer, it records its context and registers itself as a singleton so the Java Agent panel can read the
 * claim. As listener, it refines the claim with the auto-configuration packages and the application's bean classes (for
 * the agent's {@code code-paths} sensor) when its own context is refreshed, and
 * attaches the context's {@link AgentHandoffs} so the agent starts propagating requests' context, and disarms it when its own context closes, after ending the run's Side Effects, or the application fails to start. It also disarms when the context destroys
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
    private volatile SideEffectsService sideEffects;

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
                    // Side Effects freezes the run's keys while the claim still says which sensors recorded (M5-7b).
                    SideEffectsService started = sideEffects;
                    if (started != null) {
                        started.endRun();
                    }
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
        // Code Paths' request trees from the code-paths sensor (PLAN-v2 §5.14); the bean stops routing at close.
        CodePathsService codePaths =
                applicationContext.getBeanProvider(CodePathsService.class).getIfUnique();
        if (codePaths != null) {
            codePaths.start();
        }
        // Caught exceptions into the runtime journal (PLAN-v2 M5-6a), when the claim asked for the sensor.
        AgentCaughtExceptions caught =
                applicationContext.getBeanProvider(AgentCaughtExceptions.class).getIfUnique();
        if (caught != null) {
            caught.start();
        }
        // Side Effects' rows from the side-effect sensors (PLAN-v2 §5.16); the bean stops routing at close.
        SideEffectsService sideEffects =
                applicationContext.getBeanProvider(SideEffectsService.class).getIfUnique();
        if (sideEffects != null) {
            sideEffects.start();
            this.sideEffects = sideEffects;
        }
    }

    private void refine(ConfigurableListableBeanFactory beanFactory) {
        if (!claim.armed()) {
            return;
        }
        List<String> packages =
                AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory) : List.of();
        List<String> applicationPackages = new ArrayList<>(claim.claimedPackages());
        for (String name : packages) {
            if (!applicationPackages.contains(name)) {
                applicationPackages.add(name);
            }
        }
        claim.refine(packages, beanClasses(beanFactory, applicationPackages));
    }

    /**
     * The user classes of the context's beans in the application's packages ({@code docs/PLAN-v2.md} M5-4a): a
     * singleton's ultimate target class through any AOP proxy, else the bean definition's type without initializing a
     * factory bean, with CGLIB subclasses resolved to the user class. Interfaces, lambdas and other hidden or synthetic
     * classes, configuration-properties holders, whether their class or the {@code @Bean} method creating them carries
     * {@code @ConfigurationProperties}, and beans whose type cannot be read are left out. Never throws.
     */
    static List<String> beanClasses(ConfigurableListableBeanFactory beanFactory, List<String> packages) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> configurationProperties = new HashSet<>();
        if (packages.isEmpty()) {
            return List.of();
        }
        String[] definitions;
        try {
            definitions = beanFactory.getBeanDefinitionNames();
        } catch (RuntimeException ex) {
            return List.of();
        }
        for (String name : definitions) {
            try {
                Class<?> type;
                Object singleton = beanFactory.containsSingleton(name) ? beanFactory.getSingleton(name) : null;
                if (singleton != null) {
                    type = AopProxyUtils.ultimateTargetClass(singleton);
                } else {
                    type = beanFactory.getType(name, false);
                }
                if (type == null) {
                    continue;
                }
                type = ClassUtils.getUserClass(type);
                if (type.isInterface()
                        || type.isArray()
                        || type.isPrimitive()
                        || type.isHidden()
                        || type.isSynthetic()
                        || !inPackages(type.getName(), packages)) {
                    continue;
                }
                if (AnnotatedElementUtils.hasAnnotation(type, ConfigurationProperties.class)
                        || beanFactory.findAnnotationOnBean(name, ConfigurationProperties.class, false) != null) {
                    // A configuration holder, as a @Bean @ConfigurationProperties method's: left out wherever else
                    // its class is a bean too, since the agent instruments by class.
                    configurationProperties.add(type.getName());
                    continue;
                }
                names.add(type.getName());
            } catch (RuntimeException | LinkageError ex) {
                // A bean whose type cannot be read: its methods stay untimed.
            }
        }
        names.removeAll(configurationProperties);
        return List.copyOf(names);
    }

    private static boolean inPackages(String className, List<String> packages) {
        for (String name : packages) {
            if (className.startsWith(name + ".")) {
                return true;
            }
        }
        return false;
    }
}
