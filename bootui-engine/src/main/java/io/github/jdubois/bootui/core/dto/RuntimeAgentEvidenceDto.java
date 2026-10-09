package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * What the BootUI agent's evidence kept outside the runtime journal retains, beside the journal's own bytes
 * ({@code docs/PLAN-v2.md} §5.17, M5-11).
 *
 * @param retainedBytes the estimated bytes every store retains, hidden stores included
 * @param maxBytes the bound the stores size themselves to ({@code bootui.runtime-journal.agent-evidence-max-bytes})
 * @param stores each store: its bytes and counts while its panel is visible, else only that it is hidden
 */
public record RuntimeAgentEvidenceDto(long retainedBytes, long maxBytes, List<RuntimeAgentEvidenceStoreDto> stores) {

    public RuntimeAgentEvidenceDto {
        stores = DtoCollections.immutableCopy(stores);
    }
}
