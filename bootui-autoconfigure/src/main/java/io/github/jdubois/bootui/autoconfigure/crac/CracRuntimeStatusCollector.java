package io.github.jdubois.bootui.autoconfigure.crac;

import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
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

    private final Environment environment;
    private final Supplier<List<String>> jvmArgumentsSupplier;
    private final ClassPresenceCheck classPresenceCheck;

    CracRuntimeStatusCollector(Environment environment) {
        this(environment, defaultJvmArguments(), CracRuntimeStatusCollector::isClassPresent);
    }

    CracRuntimeStatusCollector(
            Environment environment,
            Supplier<List<String>> jvmArgumentsSupplier,
            ClassPresenceCheck classPresenceCheck) {
        this.environment = environment;
        this.jvmArgumentsSupplier = jvmArgumentsSupplier;
        this.classPresenceCheck = classPresenceCheck;
    }

    CracRuntimeStatusDto collect() {
        boolean cracApiPresent = classPresenceCheck.isPresent(CRAC_API_MARKER);
        boolean cracCapableJvm = CRAC_IMPL_MARKERS.stream().anyMatch(classPresenceCheck::isPresent);
        String jvmName = jvmName();
        boolean checkpointOnRefresh = checkpointOnRefresh();

        List<String> jvmArguments = safeJvmArguments();
        String checkpointTo = argumentValue(jvmArguments, CHECKPOINT_TO_PREFIX);
        String restoreFrom = argumentValue(jvmArguments, RESTORE_FROM_PREFIX);
        List<String> cracJvmArgs = cracArguments(jvmArguments);

        String summary = summary(cracApiPresent, cracCapableJvm, checkpointOnRefresh, checkpointTo);
        return new CracRuntimeStatusDto(
                cracApiPresent,
                cracCapableJvm,
                jvmName,
                checkpointOnRefresh,
                checkpointTo,
                restoreFrom,
                cracJvmArgs,
                summary);
    }

    private boolean checkpointOnRefresh() {
        if (environment == null) {
            return false;
        }
        String value = environment.getProperty("spring.context.checkpoint");
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
            boolean cracApiPresent, boolean cracCapableJvm, boolean checkpointOnRefresh, String checkpointTo) {
        if (!cracApiPresent) {
            return "The org.crac API is not on the classpath; add the org.crac:crac dependency (bundled with "
                    + "spring-boot-starter) to use Coordinated Restore at Checkpoint.";
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
        return "This JVM is CRaC-capable. Trigger a checkpoint with jcmd <pid> JDK.checkpoint or by setting "
                + "spring.context.checkpoint=onRefresh, and run the readiness checks below first.";
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
