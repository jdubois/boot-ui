package io.github.jdubois.bootui.autoconfigure.crac;

import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.core.SpringProperties;
import org.springframework.core.env.Environment;

/**
 * Collects the live CRaC runtime status for the host process: whether the {@code org.crac} API is on
 * the classpath, whether the JVM provides a real CRaC implementation (a CRaC-enabled JDK) rather than
 * the no-op shim, whether an automatic checkpoint-on-refresh is configured, and the CRaC-related JVM
 * input arguments. The collector reads only local, in-process state and never triggers a checkpoint.
 */
final class CracRuntimeStatusCollector {

    private static final String CRAC_API_MARKER = "org.crac.Core";
    // The real CRaC implementation classes that org.crac delegates to on a CRaC-enabled JDK. When
    // none of these are present, org.crac falls back to a no-op shim and a checkpoint is not possible.
    private static final List<String> CRAC_IMPL_MARKERS = List.of("jdk.crac.Core", "javax.crac.Core");

    private static final String CHECKPOINT_TO_PREFIX = "-XX:CRaCCheckpointTo=";
    private static final String RESTORE_FROM_PREFIX = "-XX:CRaCRestoreFrom=";
    private static final String EXIT_PROPERTY = "spring.context.exit";

    private final Environment environment;
    private final Supplier<List<String>> jvmArgumentsSupplier;
    private final ClassPresenceCheck classPresenceCheck;
    private final Supplier<CracRuntimeInventory> inventorySupplier;
    private final Supplier<String> actualCheckpointPropertySupplier;
    private final Supplier<String> actualExitPropertySupplier;

    CracRuntimeStatusCollector(Environment environment) {
        this(environment, CracRuntimeInventory::empty);
    }

    CracRuntimeStatusCollector(Environment environment, Supplier<CracRuntimeInventory> inventorySupplier) {
        this(environment, defaultJvmArguments(), CracRuntimeStatusCollector::isClassPresent, inventorySupplier);
    }

    CracRuntimeStatusCollector(
            Environment environment,
            Supplier<List<String>> jvmArgumentsSupplier,
            ClassPresenceCheck classPresenceCheck) {
        this(environment, jvmArgumentsSupplier, classPresenceCheck, CracRuntimeInventory::empty);
    }

    CracRuntimeStatusCollector(
            Environment environment,
            Supplier<List<String>> jvmArgumentsSupplier,
            ClassPresenceCheck classPresenceCheck,
            Supplier<CracRuntimeInventory> inventorySupplier) {
        this(
                environment,
                jvmArgumentsSupplier,
                classPresenceCheck,
                inventorySupplier,
                defaultSpringPropertySupplier("spring.context.checkpoint"),
                defaultSpringPropertySupplier(EXIT_PROPERTY));
    }

    /**
     * Fullest constructor. {@code actualCheckpointPropertySupplier} reads the property that actually
     * controls Spring Framework's automatic checkpoint-on-refresh — see {@link #checkpointOnRefresh()}
     * for why this is deliberately not the Spring Boot {@link Environment}.
     */
    CracRuntimeStatusCollector(
            Environment environment,
            Supplier<List<String>> jvmArgumentsSupplier,
            ClassPresenceCheck classPresenceCheck,
            Supplier<CracRuntimeInventory> inventorySupplier,
            Supplier<String> actualCheckpointPropertySupplier) {
        this(
                environment,
                jvmArgumentsSupplier,
                classPresenceCheck,
                inventorySupplier,
                actualCheckpointPropertySupplier,
                defaultSpringPropertySupplier(EXIT_PROPERTY));
    }

    CracRuntimeStatusCollector(
            Environment environment,
            Supplier<List<String>> jvmArgumentsSupplier,
            ClassPresenceCheck classPresenceCheck,
            Supplier<CracRuntimeInventory> inventorySupplier,
            Supplier<String> actualCheckpointPropertySupplier,
            Supplier<String> actualExitPropertySupplier) {
        this.environment = environment;
        this.jvmArgumentsSupplier = jvmArgumentsSupplier;
        this.classPresenceCheck = classPresenceCheck;
        this.inventorySupplier = inventorySupplier;
        this.actualCheckpointPropertySupplier = actualCheckpointPropertySupplier;
        this.actualExitPropertySupplier = actualExitPropertySupplier;
    }

    CracRuntimeStatusDto collect() {
        boolean cracApiPresent = classPresenceCheck.isPresent(CRAC_API_MARKER);
        boolean cracCapableJvm = CRAC_IMPL_MARKERS.stream().anyMatch(classPresenceCheck::isPresent);
        String jvmName = jvmName();
        boolean checkpointOnRefresh = checkpointOnRefresh();
        boolean exitOnRefresh = isOnRefresh(safeProperty(actualExitPropertySupplier));

        List<String> jvmArguments = safeJvmArguments();
        String checkpointTo = argumentValue(jvmArguments, CHECKPOINT_TO_PREFIX);
        String restoreFrom = argumentValue(jvmArguments, RESTORE_FROM_PREFIX);
        List<String> cracJvmArgs = cracArguments(jvmArguments);

        String summary = summary(cracApiPresent, cracCapableJvm, checkpointOnRefresh, exitOnRefresh, checkpointTo);
        List<String> restoreCaveats = restoreCaveats(checkpointOnRefresh, exitOnRefresh);
        return new CracRuntimeStatusDto(
                cracApiPresent,
                cracCapableJvm,
                jvmName,
                checkpointOnRefresh,
                checkpointTo,
                restoreFrom,
                cracJvmArgs,
                summary,
                restoreCaveats);
    }

    /**
     * Builds the human-readable caveats shown alongside the runtime status. CRaC reads configuration
     * (environment variables, system properties, the active profile) when the checkpoint is taken and
     * freezes it into the image, and any connection pool must be reachable both when the checkpoint is
     * taken and when it is restored. These are review prompts, not detected failures.
     */
    private List<String> restoreCaveats(boolean checkpointOnRefresh, boolean exitOnRefresh) {
        List<String> caveats = new ArrayList<>();
        if (exitOnRefresh) {
            caveats.add("spring.context.exit=onRefresh is active. Spring will run the same lifecycle stop phase used "
                    + "before a checkpoint and then exit without creating a CRaC image. Use this dry-run mode on a "
                    + "regular JDK to find shutdown/lifecycle issues before attempting a real checkpoint.");
        }
        if (checkpointOnRefresh) {
            caveats.add("Configuration is frozen into the checkpoint. Environment variables, system properties, and "
                    + "the active Spring profile are read when the checkpoint is taken, not when it is restored; "
                    + "changing them for a restore-only start has no effect until a new checkpoint is taken.");
        } else if (environmentClaimsCheckpointOnRefresh()) {
            caveats.add("spring.context.checkpoint=onRefresh is set in the Spring Environment (for example "
                    + "application.yml, application.properties, or an OS environment variable), but Spring "
                    + "Framework's DefaultLifecycleProcessor only honors this property through "
                    + "org.springframework.core.SpringProperties: a JVM system property "
                    + "(-Dspring.context.checkpoint=onRefresh) or a classpath spring.properties file, never the "
                    + "Spring Boot Environment. No automatic checkpoint will actually be taken on context refresh; "
                    + "set the property as a JVM system property or in a classpath spring.properties file instead.");
        }
        List<String> poolBeans = safeInventory().connectionPoolBeans();
        if (!poolBeans.isEmpty()) {
            caveats.add("Detected " + poolBeans.size() + " connection pool bean(s) (" + String.join(", ", poolBeans)
                    + "). The backing service must be reachable both when the checkpoint is taken and when it is "
                    + "restored, and no pooled connection may be open at checkpoint time (see check CRAC-POOL-001).");
        }
        return List.copyOf(caveats);
    }

    private CracRuntimeInventory safeInventory() {
        try {
            CracRuntimeInventory inventory = inventorySupplier.get();
            return inventory == null ? CracRuntimeInventory.empty() : inventory;
        } catch (RuntimeException ex) {
            return CracRuntimeInventory.empty();
        }
    }

    /**
     * Whether an automatic checkpoint is actually taken on context refresh. Spring Framework's {@code
     * DefaultLifecycleProcessor} reads {@code spring.context.checkpoint} only through {@code
     * org.springframework.core.SpringProperties} — a JVM system property or a classpath {@code
     * spring.properties} file — and never through the Spring Boot {@link Environment}. Setting the
     * property in {@code application.yml}/{@code application.properties} (or as a plain OS environment
     * variable picked up by the Environment) has no effect on this behavior, so this method
     * deliberately reads the same source Spring Framework itself reads, rather than the Environment,
     * to avoid reporting an automatic checkpoint that will not actually happen. See {@link
     * #environmentClaimsCheckpointOnRefresh()} for the check that detects that specific mismatch.
     */
    private boolean checkpointOnRefresh() {
        return isOnRefresh(safeProperty(actualCheckpointPropertySupplier));
    }

    /**
     * Whether the Spring Boot {@link Environment} reports {@code spring.context.checkpoint=onRefresh}
     * (for example from {@code application.yml} or an OS environment variable). This does NOT mean an
     * automatic checkpoint will actually be taken — see {@link #checkpointOnRefresh()} — it exists only
     * to detect and warn about that common mismatch in {@link #restoreCaveats(boolean, boolean)}.
     */
    private boolean environmentClaimsCheckpointOnRefresh() {
        return environment != null && isOnRefresh(environment.getProperty("spring.context.checkpoint"));
    }

    private static String safeProperty(Supplier<String> supplier) {
        try {
            return supplier == null ? null : supplier.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isOnRefresh(String value) {
        return value != null && "onRefresh".equalsIgnoreCase(value.trim());
    }

    private List<String> safeJvmArguments() {
        try {
            List<String> arguments = jvmArgumentsSupplier.get();
            return arguments == null ? List.of() : arguments;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static List<String> cracArguments(List<String> jvmArguments) {
        List<String> matches = new ArrayList<>();
        for (String argument : jvmArguments) {
            if (argument != null && argument.contains("CRaC")) {
                matches.add(argument);
            }
        }
        return List.copyOf(matches);
    }

    private static String argumentValue(List<String> jvmArguments, String prefix) {
        for (String argument : jvmArguments) {
            if (argument != null && argument.startsWith(prefix)) {
                String value = argument.substring(prefix.length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String jvmName() {
        String name = System.getProperty("java.vm.name");
        return name == null || name.isBlank() ? "Unknown JVM" : name;
    }

    private static String summary(
            boolean cracApiPresent,
            boolean cracCapableJvm,
            boolean checkpointOnRefresh,
            boolean exitOnRefresh,
            String checkpointTo) {
        if (exitOnRefresh) {
            return "spring.context.exit=onRefresh is active: Spring will exercise the checkpoint lifecycle stop phase "
                    + "and exit without writing a checkpoint image. This is a safe dry run that works on a regular JDK.";
        }
        if (!cracApiPresent) {
            return "The org.crac API is not on the classpath; add the org.crac:crac dependency (its version is "
                    + "managed by the Spring Boot BOM) to use Coordinated Restore at Checkpoint.";
        }
        if (!cracCapableJvm) {
            return "The org.crac API is present but this JVM has no CRaC implementation, so checkpointing is a no-op. "
                    + "Run on a CRaC-enabled JDK (for example Azul Zulu CRaC or BellSoft Liberica) to take checkpoints.";
        }
        if (checkpointOnRefresh) {
            return "This JVM is CRaC-capable and spring.context.checkpoint=onRefresh is set, so an automatic "
                    + "checkpoint is taken once the application context refreshes"
                    + (checkpointTo == null ? "." : " into " + checkpointTo + ".");
        }
        return "This JVM is CRaC-capable. Trigger a checkpoint with jcmd <pid> JDK.checkpoint, or take one "
                + "automatically on context refresh by setting spring.context.checkpoint=onRefresh as a JVM "
                + "system property (-Dspring.context.checkpoint=onRefresh) or in a classpath spring.properties "
                + "file. Setting it in application.yml/application.properties alone has no effect, since Spring "
                + "Framework's checkpoint-on-refresh support reads this property through "
                + "org.springframework.core.SpringProperties, not the Spring Boot Environment. Run the readiness "
                + "checks below first.";
    }

    private static Supplier<List<String>> defaultJvmArguments() {
        return () -> {
            try {
                RuntimeMXBean runtimeMxBean = java.lang.management.ManagementFactory.getRuntimeMXBean();
                return runtimeMxBean == null ? List.of() : runtimeMxBean.getInputArguments();
            } catch (RuntimeException ex) {
                return List.of();
            }
        };
    }

    /**
     * Reads {@code spring.context.checkpoint} the same way Spring Framework's {@code
     * DefaultLifecycleProcessor} does: through {@code org.springframework.core.SpringProperties}, which
     * consults a JVM system property or a classpath {@code spring.properties} file, never the Spring
     * Boot {@link Environment}.
     */
    private static Supplier<String> defaultSpringPropertySupplier(String name) {
        return () -> {
            try {
                return SpringProperties.getProperty(name);
            } catch (RuntimeException | LinkageError ex) {
                return null;
            }
        };
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, CracRuntimeStatusCollector.class.getClassLoader());
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    /** Abstraction over classpath presence so the collector can be exercised deterministically in tests. */
    interface ClassPresenceCheck {
        boolean isPresent(String className);
    }
}
