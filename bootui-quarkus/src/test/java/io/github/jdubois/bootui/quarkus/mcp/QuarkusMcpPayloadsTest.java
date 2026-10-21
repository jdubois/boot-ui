package io.github.jdubois.bootui.quarkus.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.engine.mcp.McpLowFootprint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuarkusMcpPayloadsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void metadataIsAdditiveAndOnlyEffectiveCaptureIsAdjusted() {
        var payload =
                Map.of("entries", List.of(), "capturing", true, "nativeCount", 23, "page", Map.of("hasMore", true));
        var result = new McpLowFootprint.Result(payload, Map.of("active", true, "paused", true), true);
        var tree = mapper.valueToTree(QuarkusMcpPayloads.render(result, mapper));
        assertThat(tree.path("entries").isEmpty()).isTrue();
        assertThat(tree.path("capturing").asBoolean()).isFalse();
        assertThat(tree.path("nativeCount").asInt()).isEqualTo(23);
        assertThat(tree.path("page").path("hasMore").asBoolean()).isTrue();
        assertThat(tree.path("lowFootprint").path("paused").asBoolean()).isTrue();
        assertThat(payload.get("capturing")).isEqualTo(true);
        assertThat(QuarkusMcpPayloads.render(payload, mapper)).isSameAs(payload);
        assertThat(mapper.valueToTree(QuarkusMcpPayloads.render(
                                new McpLowFootprint.Result(payload, Map.of("paused", false), false), mapper))
                        .path("capturing")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void malformedAugmentedPayloadIsAnExplicitFailure() {
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> QuarkusMcpPayloads.render(new McpLowFootprint.Result(List.of(), Map.of(), true), mapper));
    }
}
