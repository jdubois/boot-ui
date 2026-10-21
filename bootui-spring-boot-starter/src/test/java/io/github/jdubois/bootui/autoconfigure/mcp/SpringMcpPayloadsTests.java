package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.jdubois.bootui.engine.mcp.McpLowFootprint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SpringMcpPayloadsTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void metadataIsAdditiveAndOnlyEffectiveCaptureIsAdjusted() {
        var payload =
                Map.of("entries", List.of(), "capturing", true, "nativeCount", 23, "page", Map.of("hasMore", true));
        var result = new McpLowFootprint.Result(payload, Map.of("active", true, "paused", true), true);
        var tree = mapper.valueToTree(SpringMcpPayloads.render(result, mapper));
        assertThat(tree.path("entries").isEmpty()).isTrue();
        assertThat(tree.path("capturing").asBoolean()).isFalse();
        assertThat(tree.path("nativeCount").asInt()).isEqualTo(23);
        assertThat(tree.path("page").path("hasMore").asBoolean()).isTrue();
        assertThat(tree.path("lowFootprint").path("paused").asBoolean()).isTrue();
        assertThat(payload.get("capturing")).isEqualTo(true);
        assertThat(SpringMcpPayloads.render(payload, mapper)).isSameAs(payload);
        assertThat(mapper.valueToTree(SpringMcpPayloads.render(
                                new McpLowFootprint.Result(payload, Map.of("paused", false), false), mapper))
                        .path("capturing")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void malformedAugmentedPayloadIsAnExplicitFailure() {
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> SpringMcpPayloads.render(new McpLowFootprint.Result(List.of(), Map.of(), true), mapper));
    }
}
