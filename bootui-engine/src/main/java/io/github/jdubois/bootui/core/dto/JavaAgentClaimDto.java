package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The BootUI Java agent's current claim, as the bootstrap bridge reports it ({@code docs/PLAN-v2.md} §5.13, D34).
 *
 * @param generation the claim's generation, increasing with every claim and release in the JVM
 * @param owner the claiming application run, {@code application@id}
 * @param application the claiming application's name
 * @param mode {@code dev} or {@code test}
 * @param armedAt when the claim was made, in epoch milliseconds, or {@code null} when unknown
 * @param packages the application package prefixes the agent may instrument
 * @param armed whether the claim is armed: false once its run ended (disarmed)
 * @param abandoned whether the claiming run is gone without disarming, so another application may take it over
 */
public record JavaAgentClaimDto(
        long generation,
        String owner,
        String application,
        String mode,
        Long armedAt,
        List<String> packages,
        boolean armed,
        boolean abandoned) {

    public JavaAgentClaimDto {
        packages = DtoCollections.immutableCopy(packages);
    }
}
