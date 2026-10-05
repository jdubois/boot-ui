package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition;
import io.github.jdubois.bootui.autoconfigure.config.BootUiActuatorDefaultsEnvironmentPostProcessor;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentPackages;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import java.time.Duration;
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
 * again in the same slot, replacing the previous run's claim; nothing static keeps a run's objects. A
 * {@code SpringApplication} run inside another one, as Spring Cloud's bootstrap context, neither claims nor releases.
 */
public class BootUiAgentClaimEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Log LOGGER = LogFactory.getLog(BootUiAgentClaimEnvironmentPostProcessor.class);

    static final List<String> TEST_FRAMEWORKS =
            List.of("org.junit.", "org.springframework.boot.test.", "org.testng.", "io.cucumber.");

    private static final String SPRING_APPLICATION = SpringApplication.class.getName();

    /** {@code SpringApplication.run(String...)}, the instance method each run goes through once. */
    private static final String RUN_DESCRIPTOR =
            "([Ljava/lang/String;)Lorg/springframework/context/ConfigurableApplicationContext;";

    /** Spring Cloud's {@code BootstrapApplicationListener.BOOTSTRAP_PROPERTY_SOURCE_NAME}, without its dependency. */
    private static final String SPRING_CLOUD_BOOTSTRAP = "bootstrap";

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
     * Breaks start-up for one reason only: with the agent attached and BootUI enabled, {@code bootui.agent.sensors}
     * names a sensor no version of the agent lists, which the application's developer asked for and must fix, as a
     * Quarkus application fails to start for it. A sensor Side Effects lists but this version does not ship is accepted
     * with a warning. This runs in every JVM with the BootUI starter, production included, so any other failure (an
     * unexpected launcher) leaves the agent unclaimed and the application starting.
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            AgentBridgeAccess access = bridge.get();
            if (!access.present()) {
                return;
            }
            if (nestedRun()) {
                LOGGER.debug("Not claiming or releasing the BootUI agent from a SpringApplication run inside another");
                return;
            }
            if (springCloudBootstrap(environment)) {
                LOGGER.debug("Not claiming or releasing the BootUI agent from an environment with a property source"
                        + " named '" + SPRING_CLOUD_BOOTSTRAP + "' (Spring Cloud's bootstrap context)");
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
            AgentSensorSettings sensors;
            try {
                sensors = sensors(environment);
            } catch (IllegalArgumentException ex) {
                throw new InvalidAgentSensors(ex);
            }
            String notAvailable = sensors.notAvailableWarning();
            if (notAvailable != null) {
                LOGGER.warn(notAvailable);
            }
            AgentClaim claim = AgentClaim.claim(
                    access, name, owner(name), mode, packages(environment, application, mode), sensors);
            AgentClaimOwner owner = new AgentClaimOwner(claim);
            application.addInitializers(owner);
            application.addListeners(owner);
        } catch (InvalidAgentSensors ex) {
            // Invalid input fails the start, as it fails a Quarkus start, rather than silently claiming nothing.
            throw ex;
        } catch (RuntimeException | LinkageError ex) {
            LOGGER.debug("Could not claim the BootUI agent", ex);
        }
    }

    /**
     * Whether this {@code SpringApplication} runs inside another one's {@code run}, as Spring Cloud's bootstrap context
     * does while the application prepares its environment, and again for each legacy refresh: such a run is not the
     * application, so it neither claims (its sources' packages would stay instrumented) nor releases (it would remove
     * the application's claim, or the claim a DevTools restart keeps armed, D34).
     */
    static boolean nestedRun() {
        // The descriptor tells the instance run(String...) from the static run methods, and needs class references.
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                        .walk(frames -> frames.filter(frame -> SPRING_APPLICATION.equals(frame.getClassName())
                                        && "run".equals(frame.getMethodName())
                                        && RUN_DESCRIPTOR.equals(frame.getDescriptor()))
                                .limit(2)
                                .count())
                > 1;
    }

    /** Spring Cloud's bootstrap context, whose environment carries a property source named {@code bootstrap}. */
    static boolean springCloudBootstrap(ConfigurableEnvironment environment) {
        return environment.getPropertySources().contains(SPRING_CLOUD_BOOTSTRAP);
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

    /**
     * {@code bootui.agent.sensors}, {@code bootui.agent.executors.*}, and {@code bootui.agent.ring-capacity}, with Reactor's scheduler threads added to the
     * skipped threads when Reactor's automatic context propagation carries BootUI's context across them already.
     */
    static AgentSensorSettings sensors(ConfigurableEnvironment environment) {
        Binder binder = Binder.get(environment);
        AgentSensorSettings defaults = AgentSensorSettings.defaults();
        AgentSensorSettings settings = new AgentSensorSettings(
                binder.bind("bootui.agent.sensors", Bindable.listOf(String.class))
                        .orElse(defaults.sensors()),
                binder.bind("bootui.agent.executors.skip-tasks", Bindable.listOf(String.class))
                        .orElse(defaults.skipTasks()),
                binder.bind("bootui.agent.executors.skip-threads", Bindable.listOf(String.class))
                        .orElse(defaults.skipThreads()),
                binder.bind("bootui.agent.executors.max-handoff", Bindable.of(Duration.class))
                        .orElse(defaults.maxHandoff()),
                binder.bind("bootui.agent.ring-capacity", Bindable.of(Integer.class))
                        .orElse(defaults.ringCapacity()));
        return reactorPropagatesContext(environment)
                ? settings.withSkipThreads(AgentSensorSettings.REACTOR_SKIP_THREADS)
                : settings;
    }

    /**
     * Whether Reactor's automatic context propagation is on, or will be: {@code Hooks.isAutomaticContextPropagationEnabled()}
     * when Reactor is present, else {@code spring.reactor.context-propagation=auto}, which Spring Boot turns it on with
     * once the context starts and BootUI contributes for reactive applications.
     */
    static boolean reactorPropagatesContext(ConfigurableEnvironment environment) {
        if ("auto"
                .equalsIgnoreCase(environment
                        .getProperty(
                                BootUiActuatorDefaultsEnvironmentPostProcessor.REACTOR_CONTEXT_PROPAGATION_PROPERTY, "")
                        .trim())) {
            return true;
        }
        try {
            Class<?> hooks = Class.forName(
                    "reactor.core.publisher.Hooks",
                    false,
                    BootUiAgentClaimEnvironmentPostProcessor.class.getClassLoader());
            Object enabled =
                    hooks.getMethod("isAutomaticContextPropagationEnabled").invoke(null);
            return Boolean.TRUE.equals(enabled);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return false;
        }
    }

    private static void add(List<String> packages, String name) {
        if (name != null && !name.isBlank() && !packages.contains(name.trim())) {
            packages.add(name.trim());
        }
    }

    private static String owner(String application) {
        return application + "@" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** {@code bootui.agent.sensors} names an unknown sensor: the application does not start. */
    static final class InvalidAgentSensors extends IllegalStateException {

        InvalidAgentSensors(IllegalArgumentException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
