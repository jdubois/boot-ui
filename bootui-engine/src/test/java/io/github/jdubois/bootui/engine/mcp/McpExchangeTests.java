package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Serve;
import io.github.jdubois.bootui.engine.mcp.McpExchange.Envelope;
import io.github.jdubois.bootui.engine.mcp.McpExchange.IdEcho;
import io.github.jdubois.bootui.engine.mcp.McpExchange.IdShape;
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
                        Envelope.notAnObject(true),
                        new McpRequestHeaders(List.of("2099-01-01"), List.of(), List.of()),
                        false))
                .isEqualTo(new Plan.Reject(
                        McpEra.LEGACY,
                        400,
                        McpProtocol.INVALID_REQUEST,
                        McpProtocol.BATCH_NOT_SUPPORTED_MESSAGE,
                        List.of(),
                        null,
                        IdEcho.NULL));
    }

    @Test
    void eraRejectionsPrecedeTheDisabledShortCircuitAndCarryWireCodes() {
        McpRequestHeaders mismatch = new McpRequestHeaders(List.of("2026-07-28"), List.of("tools/call"), List.of());
        assertThat(McpExchange.plan(modern("tools/list"), mismatch, false))
                .isEqualTo(new Plan.Reject(
                        McpEra.MODERN,
                        400,
                        McpProtocol.HEADER_MISMATCH,
                        McpProtocol.METHOD_HEADER_MISMATCH_MESSAGE,
                        List.of(),
                        null,
                        IdEcho.READABLE));
        Plan legacy = McpExchange.plan(
                legacy("ping"), new McpRequestHeaders(List.of("2099-01-01"), List.of(), List.of()), true);
        assertThat(((Plan.Reject) legacy).idEcho())
                .as("legacy refusals keep a null id")
                .isEqualTo(IdEcho.NULL);
    }

    @Test
    void aDisabledServerAcceptsNotificationsAndRefusesRequestsInTheirEra() {
        Envelope notification = new Envelope(
                false,
                true,
                true,
                IdShape.ABSENT_OR_NULL,
                true,
                "notifications/initialized",
                null,
                McpRequestMeta.NONE);
        assertThat(notification.notification()).isTrue();
        assertThat(McpExchange.plan(notification, McpRequestHeaders.NONE, false))
                .isEqualTo(new Plan.Accept());
        assertThat(((Plan.Disabled) McpExchange.plan(legacy("ping"), McpRequestHeaders.NONE, false)).code())
                .isEqualTo(-32000);
        assertThat(((Plan.Disabled) McpExchange.plan(modern("tools/list"), MODERN_HEADERS, false)).code())
                .isEqualTo(-31000);
        assertThat(McpExchange.plan(withJsonrpc(false), McpRequestHeaders.NONE, false))
                .as("the disabled short-circuit precedes envelope validation, as in BootUI 1.x")
                .isInstanceOf(Plan.Disabled.class);
    }

    @Test
    void envelopeRefusalsAreTwoHundredsWithBootUiOneIdEcho() {
        assertThat(McpExchange.plan(Envelope.notAnObject(false), McpRequestHeaders.NONE, true))
                .isEqualTo(reject200(McpProtocol.INVALID_REQUEST, McpProtocol.MALFORMED_REQUEST_MESSAGE, IdEcho.NULL));
        assertThat(McpExchange.plan(withJsonrpc(false), McpRequestHeaders.NONE, true))
                .isEqualTo(reject200(McpProtocol.INVALID_REQUEST, McpProtocol.MISSING_JSONRPC_MESSAGE, IdEcho.AS_SENT));
        assertThat(McpExchange.plan(
                        new Envelope(false, true, true, IdShape.INVALID, true, "ping", null, McpRequestMeta.NONE),
                        McpRequestHeaders.NONE,
                        true))
                .isEqualTo(reject200(McpProtocol.INVALID_REQUEST, McpProtocol.INVALID_ID_MESSAGE, IdEcho.NULL));
        assertThat(McpExchange.plan(
                        new Envelope(
                                false, true, true, IdShape.STRING_OR_NUMBER, false, "ping", null, McpRequestMeta.NONE),
                        McpRequestHeaders.NONE,
                        true))
                .isEqualTo(reject200(McpProtocol.INVALID_PARAMS, McpProtocol.PARAMS_OBJECT_MESSAGE, IdEcho.AS_SENT));
    }

    @Test
    void anEnabledServerDispatchesInTheResolvedEra() {
        assertThat(McpExchange.plan(modern("tools/list"), MODERN_HEADERS, true))
                .isEqualTo(new Plan.Dispatch(new Serve(McpEra.MODERN, "2026-07-28", null)));
        assertThat(McpExchange.plan(legacy("ping"), McpRequestHeaders.NONE, true))
                .isEqualTo(new Plan.Dispatch(new Serve(McpEra.LEGACY, null, null)));
    }

    @Test
    void oversizedResponsesAreReplacedWithTheEraWireCode() {
        assertThat(McpExchange.checkResponseSize(McpEra.LEGACY, 100, 100)).isNull();
        assertThat(McpExchange.checkResponseSize(McpEra.LEGACY, 101, 100))
                .isEqualTo(reject200(
                        McpProtocol.RESPONSE_TOO_LARGE, McpProtocol.RESPONSE_TOO_LARGE_MESSAGE, IdEcho.AS_SENT));
        assertThat(McpExchange.checkResponseSize(McpEra.MODERN, 101, 100).code())
                .isEqualTo(-31003);
    }

    private static Plan.Reject reject200(int code, String message, IdEcho echo) {
        return new Plan.Reject(McpEra.LEGACY, 200, code, message, List.of(), null, echo);
    }

    private static Envelope legacy(String method) {
        return new Envelope(false, true, true, IdShape.STRING_OR_NUMBER, true, method, null, McpRequestMeta.NONE);
    }

    private static Envelope modern(String method) {
        return new Envelope(false, true, true, IdShape.STRING_OR_NUMBER, true, method, null, MODERN_META);
    }

    private static Envelope withJsonrpc(boolean valid) {
        return new Envelope(false, true, valid, IdShape.STRING_OR_NUMBER, true, "ping", null, McpRequestMeta.NONE);
    }
}
