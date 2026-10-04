package io.github.jdubois.bootui.core.dto;

import java.util.Map;

/**
 * One store of the BootUI agent's evidence kept outside the runtime journal, such as Code Paths' trees.
 *
 * @param store the store's id, such as {@code code-paths}
 * @param panel the panel that owns its evidence
 * @param visible whether that panel is visible; a hidden store shows neither its bytes nor its counts
 * @param note why the store is hidden, or why its usage could not be read, else {@code null}
 * @param retainedBytes its estimated bytes, or {@code null} while hidden
 * @param maxBytes the bound it sizes itself to, or {@code null} while hidden
 * @param counts what it holds, by name, such as {@code requestTrees}; empty while hidden
 */
public record RuntimeAgentEvidenceStoreDto(
        String store,
        String panel,
        boolean visible,
        String note,
        Long retainedBytes,
        Long maxBytes,
        Map<String, Long> counts) {

    public RuntimeAgentEvidenceStoreDto {
        counts = DtoCollections.immutableCopy(counts);
    }
}
