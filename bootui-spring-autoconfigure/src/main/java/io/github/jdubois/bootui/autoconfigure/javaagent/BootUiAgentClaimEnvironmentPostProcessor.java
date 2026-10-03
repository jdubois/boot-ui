package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentPackages;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Claims the BootUI Java agent as early as Spring allows ({@code docs/PLAN-v2.md} D34): once the environment, and so
 * BootUI's activation, is known. Without the agent's bridge on the bootstrap class path it does nothing. When BootUI
 * resolves to disabled, or {@code bootui.agent.enabled=false}, it releases the agent, removing its transformers unless
 * another application's armed claim holds it. Otherwise it claims the agent for this run and hands the claim to an
 * {@link AgentClaimOwner}, which this run's initializers and listeners keep, and which refines and disarms it.
 *
 * <p>DevTools runs every {@code EnvironmentPostProcessor} again in each restart's class loader, so a restart claims
 * again in the same slot, replacing the previous run's claim; nothing static keeps a run's objects.
 */
public class BootUiAgentClaimEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Log LOGGER = LogFactory.getLog(BootUiAgentClaimEnvironmentPostProcessor.class);

    static final List<String> TEST_FRAMEWORKS =
            List.of("org.junit.", "org.springframework.boot.test.", "org.testng.", "io.cucumber.");

    private final Supplier<AgentBridgeAccess> bridge;

    public BootUiAgentClaimEnvironmentPostProcessor() {
        this(AgentBridgeAccess::locate);
    }

    BootUiAgentClaimEnvironmentPostProcessor(Supplier<AgentBridgeAccess> bridge) {
        this.bridge = bridge;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Never breaks start-up: this runs in every JVM with the BootUI starter, production included, so any failure
     * (a malformed property, an unexpected launcher) leaves the agent unclaimed and the application starting.
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            AgentBridgeAccess access = bridge.get();
            if (!access.present()) {
                return;
            }
            String name = applicationName(environment, application);
            String mode = mode(environment);
            boolean bootUiEnabled = BootUiActivationCondition.resolve(environment, application.getClassLoader())
                    .enabled();
            if (!bootUiEnabled || !agentEnabled(environment)) {
                AgentClaim.release(access, name, mode);
                return;
            }
            AgentClaim claim =
                    AgentClaim.claim(access, name, owner(name), mode, packages(environment, application, mode));
            AgentClaimOwner owner = new AgentClaimOwner(claim);
            application.addInitializers(owner);
            application.addListeners(owner);
        } catch (RuntimeException | LinkageError ex) {
            LOGGER.debug("Could not claim the BootUI agent", ex);
        }
    }

    private static boolean agentEnabled(ConfigurableEnvironment environment) {
        return !"false"
                .equalsIgnoreCase(
                        environment.getProperty("bootui.agent.enabled", "true").trim());
    }

    /**
     * {@code spring.application.name}, else the first class source (the {@code @SpringBootConfiguration} class, the same
     * for every test of an application, whereas Spring Boot's test loader sets each test class as the main class), else
     * the main application class.
     */
    static String applicationName(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty("spring.application.name");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        for (Object source : application.getAllSources()) {
            if (source instanceof Class<?> type) {
                return type.getName();
            }
        }
        Class<?> main = application.getMainApplicationClass();
        if (main != null) {
            return main.getName();
        }
        Iterator<Object> sources = application.getAllSources().iterator();
        return sources.hasNext() ? String.valueOf(sources.next()) : "application";
    }

    /** {@code bootui.agent.mode}, or with {@code auto}, test when a test framework started this application. */
    static String mode(ConfigurableEnvironment environment) {
        String configured =
                environment.getProperty("bootui.agent.mode", "auto").trim().toLowerCase(Locale.ROOT);
        if (AgentClaim.DEV.equals(configured) || AgentClaim.TEST.equals(configured)) {
            return configured;
        }
        return startedByTestFramework() ? AgentClaim.TEST : AgentClaim.DEV;
    }

    static boolean startedByTestFramework() {
        return StackWalker.getInstance()
                .walk(frames -> frames.anyMatch(frame -> {
                    String type = frame.getClassName();
                    for (String prefix : TEST_FRAMEWORKS) {
                        if (type.startsWith(prefix)) {
                            return true;
                        }
                    }
                    return false;
                }));
    }

    /**
     * The class sources' packages, the main application class's package when it is a source or in dev mode (in a test,
     * the deduced main class can be a launcher such as Surefire's {@code ForkedBooter}), and
     * {@code bootui.agent.packages}.
     */
    static List<String> packages(ConfigurableEnvironment environment, SpringApplication application, String mode) {
        List<String> packages = new ArrayList<>();
        Class<?> main = application.getMainApplicationClass();
        boolean mainIsSource = false;
        for (Object source : application.getAllSources()) {
            if (source instanceof Class<?> type) {
                add(packages, AgentPackages.packageOf(type.getName()));
                mainIsSource |= type == main;
            }
        }
        if (main != null && (mainIsSource || AgentClaim.DEV.equals(mode))) {
            add(packages, AgentPackages.packageOf(main.getName()));
        }
        Binder.get(environment)
                .bind("bootui.agent.packages", Bindable.listOf(String.class))
                .ifBound(extra -> extra.forEach(name -> add(packages, name)));
        return packages;
    }

    private static void add(List<String> packages, String name) {
        if (name != null && !name.isBlank() && !packages.contains(name.trim())) {
            packages.add(name.trim());
        }
    }

    private static String owner(String application) {
        return application + "@" + UUID.randomUUID().toString().substring(0, 8);
    }
}
