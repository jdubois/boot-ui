package io.github.jdubois.bootui.quarkus4.faulttolerance;

import io.quarkus.runtime.annotations.RecordableConstructor;
import java.util.List;

/**
 * One declared SmallRye / MicroProfile Fault Tolerance policy, captured at <em>build time</em> by the
 * deployment processor's Jandex scan of the application's fault-tolerance annotations and replayed into the
 * runtime via a {@code @Recorder} (see {@link FaultTolerancePoliciesRecorder}).
 *
 * <p>Capture happens at build time because SmallRye keeps no runtime registry of guarded methods: the only
 * runtime API it exposes is {@code CircuitBreakerMaintenance}, and even that can answer for a circuit breaker
 * only when the developer gave it a {@code @CircuitBreakerName}. Everything else — the annotated method, its
 * attempt budget, its thresholds — exists solely in the annotations.</p>
 *
 * <p>The declaring class and method are kept as separate components (rather than a single {@code Class#method}
 * label) because MicroProfile Fault Tolerance's configuration override keys are built from them:
 * {@code <class>/<method>/<annotation>/<member>}, {@code <class>/<annotation>/<member>} and
 * {@code <annotation>/<member>}. {@link QuarkusFaultTolerancePolicyProvider} resolves those at request time, so a
 * value overridden in {@code application.properties} is reported as {@code CONFIGURED} rather than the
 * annotation's stale literal.</p>
 *
 * <p>Serialized into the Quarkus bytecode recorder, so the canonical constructor is
 * {@link RecordableConstructor}; the module compiles with {@code -parameters} so parameter names match the
 * record components.</p>
 *
 * @param name display name of the policy, normally {@code SimpleClass#method}
 * @param type the neutral policy type, e.g. {@code CIRCUIT_BREAKER}
 * @param annotationName the fault-tolerance annotation's simple name, e.g. {@code CircuitBreaker}
 * @param className the fully qualified declaring class
 * @param methodName the guarded method, or empty for a class-level annotation (which guards every method)
 * @param circuitBreakerName the {@code @CircuitBreakerName} value, or empty when the developer gave none —
 *     without it SmallRye cannot report the breaker's live state, and BootUI says so rather than guessing
 * @param settings the declared settings, in the order the panel renders them
 */
public record RawFaultTolerancePolicy(
        String name,
        String type,
        String annotationName,
        String className,
        String methodName,
        String circuitBreakerName,
        List<RawFaultToleranceSetting> settings) {

    @RecordableConstructor
    public RawFaultTolerancePolicy(
            String name,
            String type,
            String annotationName,
            String className,
            String methodName,
            String circuitBreakerName,
            List<RawFaultToleranceSetting> settings) {
        this.name = name == null ? "" : name;
        this.type = type == null ? "" : type;
        this.annotationName = annotationName == null ? "" : annotationName;
        this.className = className == null ? "" : className;
        this.methodName = methodName == null ? "" : methodName;
        this.circuitBreakerName = circuitBreakerName == null ? "" : circuitBreakerName;
        this.settings = settings == null ? List.of() : List.copyOf(settings);
    }

    /** The {@code fqcn#method} identifier of the guarded operation, or the class alone for a class-level policy. */
    public String target() {
        return methodName.isBlank() ? className : className + "#" + methodName;
    }
}
