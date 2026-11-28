package io.github.jdubois.bootui.quarkus4.faulttolerance;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

/**
 * Quarkus recorder that replays the build-time-captured MicroProfile Fault Tolerance metadata into a runtime
 * {@link QuarkusFaultTolerancePolicies} holder.
 *
 * <p>The deployment processor's {@code registerFaultTolerancePolicies} build step scans the application's bean
 * archive for the MicroProfile Fault Tolerance annotations at build time, builds the
 * {@link RawFaultTolerancePolicy} list, and calls {@link #create(List)} from a {@code @Record(STATIC_INIT)} step;
 * the returned {@link RuntimeValue} backs a synthetic {@link QuarkusFaultTolerancePolicies} bean. This mirrors the
 * Scheduled Tasks panel's build-time-capture strategy, chosen for the same reason: the declared policy
 * metadata simply does not exist at runtime.</p>
 */
@Recorder
public class FaultTolerancePoliciesRecorder {

    /** Wraps the captured rows in a runtime holder backing the synthetic {@link QuarkusFaultTolerancePolicies} bean. */
    public RuntimeValue<QuarkusFaultTolerancePolicies> create(List<RawFaultTolerancePolicy> policies) {
        return new RuntimeValue<>(new QuarkusFaultTolerancePolicies(policies));
    }
}
