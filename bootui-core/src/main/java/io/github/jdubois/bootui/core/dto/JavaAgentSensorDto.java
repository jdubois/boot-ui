package io.github.jdubois.bootui.core.dto;

import java.util.List;
import java.util.Map;

/**
 * One sensor of the BootUI agent ({@code docs/PLAN-v2.md} §5.13, M5-2), such as {@code executors}.
 *
 * @param id the sensor id, such as {@code executors}
 * @param state the sensor's state as the agent reports it: {@code off}, {@code installing}, {@code installed}, or
 *     {@code failed}
 * @param instrumentedTypes how many types the sensor instrumented
 * @param failures the classes that failed to transform, with their reasons
 * @param durationMillis how long installing and self-testing took, or {@code null}
 * @param selfTestPassed whether its self-test passed
 * @param selfTestError why its self-test failed, or {@code null}
 * @param selfTestSteps each self-test step's result, by step
 * @param hooks the JDK hooks it instruments
 * @param failedTypes how many types failed to transform
 * @param skippedTypes how many types it skipped
 * @param executors the {@code executors} sensor's counters, or {@code null} for another sensor
 */
public record JavaAgentSensorDto(
        String id,
        String state,
        int instrumentedTypes,
        List<String> failures,
        Long durationMillis,
        boolean selfTestPassed,
        String selfTestError,
        Map<String, String> selfTestSteps,
        List<JavaAgentHookDto> hooks,
        int failedTypes,
        int skippedTypes,
        JavaAgentExecutorCountersDto executors) {

    public JavaAgentSensorDto {
        failures = DtoCollections.immutableCopy(failures);
        selfTestSteps = DtoCollections.immutableCopy(selfTestSteps);
        hooks = DtoCollections.immutableCopy(hooks);
    }

    /** A sensor known only by its id, state, and transformation results. */
    public JavaAgentSensorDto(String id, String state, int instrumentedTypes, List<String> failures) {
        this(id, state, instrumentedTypes, failures, null, false, null, Map.of(), List.of(), 0, 0, null);
    }
}
