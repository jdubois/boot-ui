package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The run a Code Inventory report describes: this application's claim on the BootUI agent.
 *
 * @param generation the claim's generation, which a DevTools restart or a Quarkus live reload advances
 * @param application the application that claimed the agent
 * @param mode {@code dev} or {@code test}
 * @param claimedAtEpochMillis when the run claimed the agent, or {@code null}
 * @param readyAtEpochMillis when the application was ready, which separates startup from later, or {@code null}
 * @param packages the application packages the agent instruments
 */
public record CodeInventoryRunDto(
        long generation,
        String application,
        String mode,
        Long claimedAtEpochMillis,
        Long readyAtEpochMillis,
        List<String> packages) {

    public CodeInventoryRunDto {
        packages = DtoCollections.immutableCopy(packages);
    }
}
