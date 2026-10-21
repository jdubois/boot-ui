package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/** The same real capture lifecycle through MCP and CLI on all three request stacks. */
final class LowFootprintContract {

    private LowFootprintContract() {}

    static void verify(BiFunction<String, String, JsonNode> call) {
        String logger =
                "com.example.LowFootprint" + UUID.randomUUID().toString().replace("-", "");
        assertThat(call.apply("get_low_footprint", "{}").path("active").asBoolean())
                .isFalse();
        Logger.getLogger(logger).warning("before-low-footprint");
        JsonNode before = call.apply("get_log_tail", "{}");
        assertThat(before.path("entries").toString()).contains(logger);
        assertThat(before.has("lowFootprint")).isFalse();
        try {
            JsonNode enabled = call.apply("enable_low_footprint", "{}");
            assertThat(enabled.path("active").asBoolean()).isTrue();
            assertThat(enabled.path("offload").path("gcRequested").asBoolean()).isTrue();
            long since = enabled.path("sinceEpochMillis").asLong();
            assertThat(since).isPositive();
            JsonNode repeated = call.apply("enable_low_footprint", "{}");
            assertThat(repeated.path("sinceEpochMillis").asLong()).isEqualTo(since);
            assertThat(repeated.path("offload").isNull()).isTrue();
            assertThat(call.apply("get_low_footprint", "{}").path("offload").isNull())
                    .isTrue();

            Logger.getLogger(logger).warning("during-low-footprint");
            JsonNode paused = call.apply("get_log_tail", "{}");
            assertThat(paused.path("entries")).isEmpty();
            assertThat(paused.path("lowFootprint").path("paused").asBoolean()).isTrue();
            assertThat(paused.path("lowFootprint").path("hint").asText()).contains("disable_low_footprint");
            for (String name : List.of("get_overview", "get_live_activity", "get_runtime_insights", "get_exceptions")) {
                assertThat(call.apply(name, "{}")
                                .path("lowFootprint")
                                .path("active")
                                .asBoolean())
                        .as(name)
                        .isTrue();
            }
            assertThat(call.apply("clear_exceptions", "{}").path("capturing").asBoolean())
                    .isFalse();
            for (String name : List.of("get_health", "get_live_memory", "get_jvm_tuning", "get_config")) {
                assertThat(call.apply(name, "{}").has("lowFootprint")).as(name).isFalse();
            }
        } finally {
            assertThat(call.apply("disable_low_footprint", "{}").path("active").asBoolean())
                    .isFalse();
        }
        assertThat(call.apply("disable_low_footprint", "{}").path("active").asBoolean())
                .isFalse();
        Logger.getLogger(logger).warning("after-low-footprint");
        JsonNode after = call.apply("get_log_tail", "{}");
        assertThat(after.path("entries").toString()).contains(logger);
        assertThat(after.has("lowFootprint")).isFalse();
        assertThat(after.toString()).contains("after-low-footprint").doesNotContain("during-low-footprint");
    }
}
