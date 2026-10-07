package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Serve;
import io.github.jdubois.bootui.engine.mcp.McpExchange.Plan;
import io.github.jdubois.bootui.engine.mcp.McpRequestMeta.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpExchangeTests {

    private static final McpRequestMeta MODERN_META =
            new McpRequestMeta(Field.VALID, "2026-07-28", Field.VALID, Field.ABSENT, null);
    private static final McpRequestHeaders MODERN_HEADERS =
            new McpRequestHeaders(List.of("2026-07-28"), List.of("tools/list"), List.of());

    @Test
    void aBatchIsRefusedBeforeAnyHeaderIsJudged() {
        assertThat(McpExchange.plan(
                        true,
                        null,
                        false,
                        null,
                        McpRequestMeta.NONE,
                        new McpRequestHeaders(List.of("2099-01-01"), List.of(), List.of()),
                        false))
                .isEqualTo(new Plan.Reject(
                        McpEra.LEGACY,
                        400,
                        McpProtocol.INVALID_REQUEST,
                        McpProtocol.BATCH_NOT_SUPPORTED_MESSAGE,
                        List.of(),
                        null,
                        false));
    }

    @Test
    void eraRejectionsPrecedeTheDisabledShortCircuitAndCarryWireCodes() {
        McpRequestHeaders mismatch = new McpRequestHeaders(List.of("2026-07-28"), List.of("tools/call"), List.of());
        Plan plan = McpExchange.plan(false, "tools/list", false, null, MODERN_META, mismatch, false);
        assertThat(plan)
                .isEqualTo(new Plan.Reject(
                        McpEra.MODERN,
                        400,
                        McpProtocol.HEADER_MISMATCH,
                        McpProtocol.METHOD_HEADER_MISMATCH_MESSAGE,
                        List.of(),
                        null,
                        true));
        Plan legacy = McpExchange.plan(
                false,
                "ping",
                false,
                null,
                McpRequestMeta.NONE,
                new McpRequestHeaders(List.of("2099-01-01"), List.of(), List.of()),
                true);
        assertThat(((Plan.Reject) legacy).echoId())
                .as("legacy refusals keep a null id")
                .isFalse();
    }

    @Test
    void aDisabledServerAcceptsNotificationsAndRefusesRequestsInTheirEra() {
        assertThat(McpExchange.plan(
                        false,
                        "notifications/initialized",
                        true,
                        null,
                        McpRequestMeta.NONE,
                        McpRequestHeaders.NONE,
                        false))
                .isEqualTo(new Plan.Accept());
        Plan.Disabled legacy = (Plan.Disabled)
                McpExchange.plan(false, "ping", false, null, McpRequestMeta.NONE, McpRequestHeaders.NONE, false);
        assertThat(legacy.code()).isEqualTo(-32000);
        Plan.Disabled modern =
                (Plan.Disabled) McpExchange.plan(false, "tools/list", false, null, MODERN_META, MODERN_HEADERS, false);
        assertThat(modern.code()).isEqualTo(-31000);
    }

    @Test
    void anEnabledServerDispatchesInTheResolvedEra() {
        assertThat(McpExchange.plan(false, "tools/list", false, null, MODERN_META, MODERN_HEADERS, true))
                .isEqualTo(new Plan.Dispatch(new Serve(McpEra.MODERN, "2026-07-28", null)));
        assertThat(McpExchange.plan(false, "ping", false, null, McpRequestMeta.NONE, McpRequestHeaders.NONE, true))
                .isEqualTo(new Plan.Dispatch(new Serve(McpEra.LEGACY, null, null)));
    }
}
