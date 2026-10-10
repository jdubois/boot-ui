package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PendingSideEffectsCutTests {

    @Test
    void primitiveOwnersReuseCanonicalDecodingAndIncludePossibleStartupWork() {
        var request = SideEffectsService.pendingCut(metadata(new long[] {1, 0xab, 0, 0, 1000}), 1, 500);
        assertThat(request.qualified()).isTrue();
        assertThat(request.unknownReason()).isNull();
        assertThat(request.owners())
                .containsExactly(new SideEffectsService.PendingOwnerId("00000000000000ab", null, false));
        var execution = SideEffectsService.pendingCut(metadata(new long[] {1, 0, 0xcd, 3, 1000}), 1, 2000);
        assertThat(execution.owners())
                .containsExactly(new SideEffectsService.PendingOwnerId(null, "00000000000000cd", true));
    }

    @Test
    void missingOlderBridgeMetadataAndMalformedFieldsNeverBecomeHealthyEmpty() {
        assertThat(SideEffectsService.pendingCut(AgentBridgeAccess.absent().sideEffectsPending(1), 1, 500)
                        .unknownReason())
                .contains("no pending");
        for (String field : List.of("generation", "revision", "writers", "owners", "qualified", "unknownReason")) {
            Map<String, Object> missing = metadata();
            missing.remove(field);
            assertThat(SideEffectsService.pendingCut(missing, 1, 500).qualified())
                    .as(field)
                    .isFalse();
            assertThat(SideEffectsService.pendingCut(missing, 1, 500).unknownReason())
                    .as(field)
                    .isNotNull();
        }
        for (Map.Entry<String, Object> malformed : Map.<String, Object>of(
                        "generation",
                        1.0,
                        "revision",
                        -1L,
                        "writers",
                        0L,
                        "unknownReason",
                        0,
                        "owners",
                        List.of("not a primitive owner"),
                        "qualified",
                        "true")
                .entrySet()) {
            Map<String, Object> metadata = metadata();
            metadata.put(malformed.getKey(), malformed.getValue());
            assertThat(SideEffectsService.pendingCut(metadata, 1, 500).qualified())
                    .as(malformed.getKey())
                    .isFalse();
        }
    }

    @Test
    void staleUnqualifiedOrOversizedOwnerPayloadsRemainUnknown() {
        for (long[] invalid : List.of(
                new long[] {2, 0xab, 0, 0, 1000}, new long[] {1, 0xab, 0, 0},
                new long[] {1, 0xab, 0, 4, 1000}, new long[] {1, 0xab, 0, 0, 0})) {
            assertThat(SideEffectsService.pendingCut(metadata(invalid), 1, 500).qualified())
                    .isFalse();
        }
        var undecodable = SideEffectsService.pendingCut(metadata(new long[] {1, 0, 0, 0, 1000}), 1, 500);
        assertThat(undecodable.unknownReason()).contains("decoded");
        Map<String, Object> oversized = metadata();
        oversized.put("owners", Collections.nCopies(1025, new long[] {1, 0xab, 0, 0, 1000}));
        assertThat(SideEffectsService.pendingCut(oversized, 1, 500).qualified()).isFalse();
    }

    private static Map<String, Object> metadata(long[]... owners) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generation", 1L);
        metadata.put("revision", 2L);
        metadata.put("writers", 0);
        metadata.put("qualified", true);
        metadata.put("unknownReason", null);
        metadata.put("owners", List.of(owners));
        return metadata;
    }
}
