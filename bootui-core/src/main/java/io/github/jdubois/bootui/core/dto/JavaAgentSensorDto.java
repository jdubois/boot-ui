package io.github.jdubois.bootui.core.dto;

import java.util.List;
import java.util.Map;

/**
 * One sensor of the BootUI agent ({@code docs/PLAN-v2.md} §5.13, M5-2, M5-3), such as {@code executors}.
 *
 * @param id the sensor id, such as {@code executors}
 * @param state the sensor's state as the agent reports it: {@code off}, {@code installing}, {@code installed}, or
 *     {@code failed}. A transformer stays installed across claims (D34), so this is the JVM's state, not the claim's
 * @param active whether this application's armed claim uses the sensor: it is in the claim's
 *     {@code bootui.agent.sensors} list and the agent reports it active for that claim
 * @param instrumentedTypes how many types the sensor instrumented
 * @param failures the classes that failed to transform, with their reasons
 * @param durationMillis how long the last install and its self-test took together, or {@code null}
 * @param installMillis how long the last install took, including its retransformation of loaded classes, or
 *     {@code null}
 * @param selfTestMillis how long the last self-test took, or {@code null}
 * @param retransformMillis the cumulative time the sensor spent installing and releasing its transformer since the JVM
 *     started, each including its retransformation of loaded classes
 * @param selfTestPassed whether its self-test passed
 * @param selfTestError why its self-test failed, or {@code null}
 * @param selfTestSteps each self-test step's result, by step
 * @param hooks the JDK hooks it instruments
 * @param failedTypes how many types failed to transform
 * @param skippedTypes how many types it skipped
 * @param transformedTypes how many classes it transformed as they loaded, cumulatively
 * @param retransformedTypes how many already loaded classes it retransformed, cumulatively
 * @param executors the {@code executors} sensor's counters, or {@code null} for another sensor
 * @param inventory the {@code inventory} sensor's counters, or {@code null} for another sensor
 */
public record JavaAgentSensorDto(
        String id,
        String state,
        boolean active,
        int instrumentedTypes,
        List<String> failures,
        Long durationMillis,
        Long installMillis,
        Long selfTestMillis,
        long retransformMillis,
        boolean selfTestPassed,
        String selfTestError,
        Map<String, String> selfTestSteps,
        List<JavaAgentHookDto> hooks,
        int failedTypes,
        int skippedTypes,
        int transformedTypes,
        int retransformedTypes,
        JavaAgentExecutorCountersDto executors,
        JavaAgentInventoryCountersDto inventory) {

    public JavaAgentSensorDto {
        failures = DtoCollections.immutableCopy(failures);
        selfTestSteps = DtoCollections.immutableCopy(selfTestSteps);
        hooks = DtoCollections.immutableCopy(hooks);
    }

    /** An inactive sensor known only by its id, state, and transformation results. */
    public JavaAgentSensorDto(String id, String state, int instrumentedTypes, List<String> failures) {
        this(
                id,
                state,
                false,
                instrumentedTypes,
                failures,
                null,
                null,
                null,
                0L,
                false,
                null,
                Map.of(),
                List.of(),
                0,
                0,
                0,
                0,
                null,
                null);
    }

    /** Every component but the inventory sensor's counters, for the propagation sensors and older callers. */
    public JavaAgentSensorDto(
            String id,
            String state,
            boolean active,
            int instrumentedTypes,
            List<String> failures,
            Long durationMillis,
            Long installMillis,
            Long selfTestMillis,
            long retransformMillis,
            boolean selfTestPassed,
            String selfTestError,
            Map<String, String> selfTestSteps,
            List<JavaAgentHookDto> hooks,
            int failedTypes,
            int skippedTypes,
            int transformedTypes,
            int retransformedTypes,
            JavaAgentExecutorCountersDto executors) {
        this(
                id,
                state,
                active,
                instrumentedTypes,
                failures,
                durationMillis,
                installMillis,
                selfTestMillis,
                retransformMillis,
                selfTestPassed,
                selfTestError,
                selfTestSteps,
                hooks,
                failedTypes,
                skippedTypes,
                transformedTypes,
                retransformedTypes,
                executors,
                null);
    }
}
