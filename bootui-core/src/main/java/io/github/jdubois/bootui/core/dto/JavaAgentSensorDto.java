package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One sensor of the BootUI Java agent ({@code docs/PLAN-v2.md} §5.13). No sensor exists before executor propagation.
 *
 * @param id the sensor id, such as {@code executor-propagation}
 * @param state the sensor's state as the agent reports it
 * @param instrumentedTypes how many types the sensor instrumented
 * @param failures the classes that failed to transform, with their reasons
 */
public record JavaAgentSensorDto(String id, String state, int instrumentedTypes, List<String> failures) {

    public JavaAgentSensorDto {
        failures = DtoCollections.immutableCopy(failures);
    }
}
