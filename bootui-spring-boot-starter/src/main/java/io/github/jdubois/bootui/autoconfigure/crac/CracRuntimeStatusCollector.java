package io.github.jdubois.bootui.autoconfigure.crac;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.core.SpringProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Passive runtime observations, not an operational checkpoint probe. API and implementation markers
 * are evidence only: no checkpoint, resource callback, lifecycle operation or mutation is invoked.
 * Resource caveats use the last explicit scan's inventory, never fresh bean discovery on GET.
 */
final class CracRuntimeStatusCollector {

    private static final String CRAC_API_MARKER = "org.crac.Core";
    private static final List<String> CRAC_IMPL_MARKERS = List.of("jdk.crac.Core", "javax.crac.Core");
    private static final String CHECKPOINT_PROPERTY = "spring.context.checkpoint";
    private static final String EXIT_PROPERTY = "spring.context.exit";
    private static final String CHECKPOINT_TO_PREFIX = "-XX:CRaCCheckpointTo=";
    private static final String RESTORE_FROM_PREFIX = "-XX:CRaCRestoreFrom=";
    private static final String ENGINE_PREFIX = "-XX:CRaCEngine=";
    private static final List<String> ARGUMENT_PREFIXES =
            List.of(CHECKPOINT_TO_PREFIX, RESTORE_FROM_PREFIX, ENGINE_PREFIX);
    private static final int MAX_PREVIEW = 5;
    private static final int MAX_TEXT = 300;
    private static final ExposurePolicy DEFAULT_EXPOSURE = new ExposurePolicy() {
        @Override
        public ValueExposure valueExposure() {
            return ValueExposure.MASKED;
        }

        @Override
        public boolean maskSecrets() {
            return true;
        }
    };

    private final Environment environment;
    private final Supplier<List<String>> jvmArgumentsSupplier;
    private final ClassPresenceCheck classPresenceCheck;
    private final Supplier<CracRuntimeInventory> inventorySupplier;
    private final Supplier<String> actualCheckpointPropertySupplier;
    private final Supplier<String> actualExitPropertySupplier;
    private final Supplier<Long> restoreTimeSupplier;
    private final ExposurePolicy exposure;
    private final SecretMasker masker = new SecretMasker();

    CracRuntimeStatusCollector(Environment environment) {
        this(environment, CracRuntimeStatusCollector::unavailableInventory);
    }

    CracRuntimeStatusCollector(Environment environment, Supplier<CracRuntimeInventory> inventorySupplier) {
        this(environment, inventorySupplier, DEFAULT_EXPOSURE, ClassUtils.getDefaultClassLoader());
    }

    CracRuntimeStatusCollector(
            Environment environment,
            Supplier<CracRuntimeInventory> inventorySupplier,
            ExposurePolicy exposure,
            ClassLoader classLoader) {
        this(
                environment,
                () -> ManagementFactory.getRuntimeMXBean().getInputArguments(),
                name -> isClassPresent(name, classLoader),
                inventorySupplier,
                () -> SpringProperties.getProperty(CHECKPOINT_PROPERTY),
                () -> SpringProperties.getProperty(EXIT_PROPERTY),
                () -> restoreTime(classLoader),
                exposure);
    }

    CracRuntimeStatusCollector(
            Environment environment,
            Supplier<List<String>> jvmArgumentsSupplier,
            ClassPresenceCheck classPresenceCheck) {
        this(environment, jvmArgumentsSupplier, classPresenceCheck, CracRuntimeStatusCollector::unavailableInventory);
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
                () -> SpringProperties.getProperty(CHECKPOINT_PROPERTY));
    }

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
                () -> SpringProperties.getProperty(EXIT_PROPERTY));
    }

    CracRuntimeStatusCollector(
            Environment environment,
            Supplier<List<String>> jvmArgumentsSupplier,
            ClassPresenceCheck classPresenceCheck,
            Supplier<CracRuntimeInventory> inventorySupplier,
            Supplier<String> actualCheckpointPropertySupplier,
            Supplier<String> actualExitPropertySupplier) {
        this(
                environment,
                jvmArgumentsSupplier,
                classPresenceCheck,
                inventorySupplier,
                actualCheckpointPropertySupplier,
                actualExitPropertySupplier,
                () -> null,
                DEFAULT_EXPOSURE);
    }

    CracRuntimeStatusCollector(
            Environment environment,
            Supplier<List<String>> jvmArgumentsSupplier,
            ClassPresenceCheck classPresenceCheck,
            Supplier<CracRuntimeInventory> inventorySupplier,
            Supplier<String> actualCheckpointPropertySupplier,
            Supplier<String> actualExitPropertySupplier,
            Supplier<Long> restoreTimeSupplier,
            ExposurePolicy exposure) {
        this.environment = environment;
        this.jvmArgumentsSupplier = jvmArgumentsSupplier;
        this.classPresenceCheck = classPresenceCheck;
        this.inventorySupplier = inventorySupplier;
        this.actualCheckpointPropertySupplier = actualCheckpointPropertySupplier;
        this.actualExitPropertySupplier = actualExitPropertySupplier;
        this.restoreTimeSupplier = restoreTimeSupplier;
        this.exposure = exposure;
    }

    CracRuntimeStatusDto collect() {
        List<String> caveats = new ArrayList<>();
        String jvmName = observe(() -> System.getProperty("java.vm.name"), "Unknown JVM", "JVM name", caveats);
        if (jvmName == null || jvmName.isBlank()) {
            jvmName = "Unknown JVM";
        }
        Boolean apiObservation =
                observe(() -> classPresenceCheck.isPresent(CRAC_API_MARKER), null, "CRaC API", caveats);
        boolean api = Boolean.TRUE.equals(apiObservation);
        boolean implementation = false;
        boolean implementationUnknown = false;
        for (String marker : CRAC_IMPL_MARKERS) {
            Boolean markerObservation =
                    observe(() -> classPresenceCheck.isPresent(marker), null, "CRaC implementation marker", caveats);
            implementation |= Boolean.TRUE.equals(markerObservation);
            implementationUnknown |= markerObservation == null;
        }
        boolean checkpoint =
                isOnRefresh(observe(actualCheckpointPropertySupplier, null, "Spring checkpoint property", caveats));
        boolean exit = isOnRefresh(observe(actualExitPropertySupplier, null, "Spring exit property", caveats));
        List<String> arguments = observe(jvmArgumentsSupplier, null, "JVM arguments", caveats);
        if (arguments == null) {
            caveats.add("JVM argument observations are unavailable.");
            arguments = List.of();
        }
        CracRuntimeInventory inventory = observe(inventorySupplier, null, "Cached runtime inventory", caveats);
        if (inventory == null) {
            inventory = unavailableInventory();
        }
        if (!inventory.available()) {
            caveats.add("Resource inventory is unavailable. Run readiness checks to collect resource evidence.");
        }
        inventory.warnings().stream()
                .limit(MAX_PREVIEW)
                .map(value -> display("crac.inventory.warning", value))
                .forEach(caveats::add);

        Long restoreTime = observe(restoreTimeSupplier, null, "Public CRaC restore-time", caveats);
        if (restoreTime != null && restoreTime >= 0) {
            caveats.add("The public CRaC MXBean reports a restore time. This does not verify application readiness.");
        } else if (argumentValue(arguments, RESTORE_FROM_PREFIX) != null) {
            caveats.add("CRaCRestoreFrom is a launch hint, not proof that this application restored successfully.");
        }
        if (implementation) {
            caveats.add("Implementation marker classes do not verify real image support, engine availability, "
                    + "OS compatibility or successful checkpoint/restore. No active probe was run.");
        }
        String engine = argumentValue(arguments, ENGINE_PREFIX);
        boolean simulated = engine != null
                && (engine.equals("simengine")
                        || engine.startsWith("simengine,")
                        || engine.equals("pauseengine")
                        || engine.startsWith("pauseengine,"));
        caveats.add(
                simulated
                        ? "simengine/pauseengine exercises limited CRaC behavior but does not create a real checkpoint image."
                        : "The effective checkpoint engine and its operational support are unverified. Check the exact "
                                + "JDK, OS and engine; CRIU prerequisites and privileges do not universally apply to Warp.");
        if (checkpoint) {
            caveats.add("spring.context.checkpoint=onRefresh is configured through SpringProperties. It is a "
                    + "one-shot initialization boundary before normal lifecycle startup; a running console cannot "
                    + "establish whether that request was consumed or that another automatic checkpoint will occur.");
            caveats.add(
                    "Already-cached configuration and bean bindings may retain checkpoint-era values even "
                            + "when a runtime updates environment or system properties at restore. No automatic rebind is implied.");
        } else if (isOnRefresh(observe(
                () -> environment == null ? null : environment.getProperty(CHECKPOINT_PROPERTY),
                null,
                "Spring Environment checkpoint property",
                caveats))) {
            caveats.add("spring.context.checkpoint=onRefresh appears only in the Spring Environment. "
                    + "DefaultLifecycleProcessor reads SpringProperties (a JVM system property or classpath "
                    + "spring.properties), not application.yml/application.properties. This Environment value "
                    + "does not request an automatic checkpoint.");
        }
        if (exit) {
            caveats.add("spring.context.exit=onRefresh calls Runtime.halt before normal lifecycle startup. "
                    + "It does not exercise checkpoint cleanup or resource callbacks and is not a cleanup dry run. "
                    + "Use only in a separate process for startup-boundary testing."
                    + (checkpoint ? " With both flags configured, checkpoint is attempted first, before exit." : ""));
        } else if (isOnRefresh(observe(
                () -> environment == null ? null : environment.getProperty(EXIT_PROPERTY),
                null,
                "Spring Environment exit property",
                caveats))) {
            caveats.add(
                    "spring.context.exit=onRefresh appears only in the Spring Environment. "
                            + "DefaultLifecycleProcessor reads this option through SpringProperties, not the Boot Environment.");
        }
        addResourceCaveats(inventory, caveats);
        String summary = apiObservation == null || (!implementation && implementationUnknown)
                ? "CRaC API/implementation observations are incomplete. Failed marker lookup does not establish "
                        + "a missing dependency or an unsupported JVM; review the evidence caveats."
                : summary(api, implementation, checkpoint, exit, simulated);
        if (caveats.stream().anyMatch(caveat -> caveat.contains("observation failed"))) {
            summary += " Some passive observations failed; review the caveats.";
        }
        List<String> displayedArguments = arguments.stream()
                .filter(argument ->
                        argument != null && ARGUMENT_PREFIXES.stream().anyMatch(argument::startsWith))
                .limit(MAX_PREVIEW)
                .map(this::displayArgument)
                .toList();
        return new CracRuntimeStatusDto(
                api,
                implementation,
                display("java.vm.name", jvmName),
                checkpoint,
                display("CRaCCheckpointTo", argumentValue(arguments, CHECKPOINT_TO_PREFIX)),
                display("CRaCRestoreFrom", argumentValue(arguments, RESTORE_FROM_PREFIX)),
                displayedArguments,
                summary,
                caveats);
    }

    private void addResourceCaveats(CracRuntimeInventory inventory, List<String> caveats) {
        if (!inventory.connectionPoolBeans().isEmpty()) {
            caveats.add(
                    "Cached connection pool/type observations requiring ownership review (CRAC-POOL-001): "
                            + preview(inventory.connectionPoolBeans())
                            + ". Definitions can be lazy or wrapped; these are not live connection counts. "
                            + "Review public lifecycle ownership and restore reconnection, not universal backend reachability.");
        }
        if (!inventory.managedConnectionPoolBeans().isEmpty()) {
            caveats.add("Cached existing singleton factories with documented lifecycle support (CRAC-POOL-001): "
                    + preview(inventory.managedConnectionPoolBeans())
                    + ". Credit is limited to owned resources in a running CRaC-enabled context; original "
                    + "on-refresh initialization and externally shared resources still require review.");
        }
        if (!inventory.hikariPoolIssues().isEmpty()) {
            caveats.add("Cached Hikari review observations (CRAC-POOL-004): "
                    + preview(inventory.hikariPoolIssues())
                    + ". These are issue observations, not a complete live pool count or verified target pairing.");
        }
    }

    private String preview(List<String> values) {
        String sample = String.join(
                ", ",
                values.stream()
                        .limit(MAX_PREVIEW)
                        .map(value -> display("crac.resource", value))
                        .toList());
        return sample + (values.size() > MAX_PREVIEW ? " (additional observations omitted)" : "");
    }

    private String displayArgument(String argument) {
        int separator = argument.indexOf('=');
        String key = argument.substring(0, separator);
        return key + "=" + display(key, argument.substring(separator + 1));
    }

    private String display(String key, String value) {
        if (value == null) {
            return null;
        }
        String result;
        if (exposure.valueExposure() == ValueExposure.METADATA_ONLY) {
            result = SecretMasker.MASKED_VALUE;
        } else if (exposure.valueExposure() == ValueExposure.FULL || !exposure.maskSecrets()) {
            result = value;
        } else {
            result = String.valueOf(masker.mask(key, value));
        }
        return result.length() > MAX_TEXT ? result.substring(0, MAX_TEXT) + "…" : result;
    }

    private static boolean isOnRefresh(String value) {
        return "onRefresh".equals(value);
    }

    private static String argumentValue(List<String> arguments, String prefix) {
        String value = null;
        for (String argument : arguments) {
            if (argument != null && argument.startsWith(prefix)) {
                value = argument.substring(prefix.length());
            }
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static String summary(
            boolean api, boolean implementation, boolean checkpoint, boolean exit, boolean simulated) {
        String result;
        if (!api) {
            result = "The org.crac API is not on the classpath; the org.crac:crac dependency version is "
                    + "managed by the Spring Boot BOM. Runtime checkpoint support has not been verified.";
        } else if (!implementation) {
            result = "The org.crac API is present, but no CRaC implementation marker was observed. With the "
                    + "unsupported org.crac 1.5 provider, checkpoint requests throw UnsupportedOperationException "
                    + "without resource notifications; checkpointing is not a harmless no-op.";
        } else {
            result = "CRaC API and implementation markers were observed; operational checkpoint support is unverified.";
        }
        if (simulated) {
            result += " A simulation/pause engine option does not establish real checkpoint images.";
        }
        if (checkpoint) {
            result += " Checkpoint-on-refresh is configured (spring.context.checkpoint=onRefresh), "
                    + "not proof of a pending or successful checkpoint.";
        }
        if (exit) {
            result += " Exit-on-refresh halts the JVM before lifecycle startup, not a cleanup dry run."
                    + (checkpoint ? " Checkpoint is attempted first when both flags are configured." : "");
        }
        return result;
    }

    private static <T> T observe(Supplier<T> supplier, T fallback, String label, List<String> caveats) {
        try {
            return supplier.get();
        } catch (RuntimeException | LinkageError ex) {
            caveats.add(label + " observation failed; this evidence is unknown.");
            return fallback;
        }
    }

    private static CracRuntimeInventory unavailableInventory() {
        return CracRuntimeInventory.unavailable("Run readiness checks to collect resource evidence.");
    }

    private static boolean isClassPresent(String name, ClassLoader classLoader) {
        try {
            Class.forName(name, false, effectiveClassLoader(classLoader));
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    /**
     * Optional org.crac 1.5 public management API. No direct optional linkage, private reflection or
     * newer Context.isImplemented call. A negative time means not restored; absence means unknown.
     */
    static Long restoreTime(ClassLoader classLoader) {
        Class<?> type;
        try {
            type = Class.forName("org.crac.management.CRaCMXBean", false, effectiveClassLoader(classLoader));
        } catch (ClassNotFoundException ex) {
            return null;
        }
        return readRestoreTime(type);
    }

    static Long readRestoreTime(Class<?> type) {
        try {
            Object bean = type.getMethod("getCRaCMXBean").invoke(null);
            Object result = type.getMethod("getRestoreTime").invoke(bean);
            if (result instanceof Long time) {
                return time;
            }
            throw new IllegalStateException("Public CRaC restore-time observation is unavailable.");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Public CRaC restore-time observation failed.");
        }
    }

    private static ClassLoader effectiveClassLoader(ClassLoader classLoader) {
        return classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader();
    }

    interface ClassPresenceCheck {
        boolean isPresent(String className);
    }
}
