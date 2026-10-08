package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Rejected;
import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Serve;
import io.github.jdubois.bootui.engine.mcp.McpRequestMeta.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpEraResolverTests {

    private static final String MODERN = "2026-07-28";
    private static final String LEGACY = "2025-06-18";

    @Test
    void requestsWithoutModernMetaKeepTheLegacyHeaderRules() {
        assertThat(resolve("tools/list", McpRequestMeta.NONE, headers(List.of(), List.of(), List.of())))
                .isEqualTo(new Serve(McpEra.LEGACY, null, null));
        assertThat(resolve("tools/list", McpRequestMeta.NONE, headers(List.of(LEGACY), List.of(), List.of())))
                .isEqualTo(new Serve(McpEra.LEGACY, LEGACY, null));
        for (List<String> header : List.of(List.of("2099-01-01"), List.of(LEGACY, LEGACY), List.of(""))) {
            assertThat(resolve("ping", McpRequestMeta.NONE, headers(header, List.of(), List.of())))
                    .isEqualTo(new Rejected(
                            McpEra.LEGACY,
                            400,
                            McpProtocol.INVALID_REQUEST,
                            McpProtocol.UNSUPPORTED_PROTOCOL_VERSION_MESSAGE,
                            List.of(),
                            null));
        }
    }

    @Test
    void aModernHeaderWithoutModernMetaIsAMalformedModernRequest() {
        assertThat(resolve(
                        "tools/list", McpRequestMeta.NONE, headers(List.of(MODERN), List.of("tools/list"), List.of())))
                .isEqualTo(
                        modernRejection(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_META_PROTOCOL_VERSION_MESSAGE));
    }

    @Test
    void initializeIsAlwaysLegacyEvenWithModernMeta() {
        assertThat(resolve("initialize", modernMeta(), headers(List.of(), List.of(), List.of())))
                .isEqualTo(new Serve(McpEra.LEGACY, null, null));
        assertThat(resolve("initialize", modernMeta(), headers(List.of(MODERN), List.of("initialize"), List.of())))
                .extracting(McpEraDecision::era, decision -> ((Rejected) decision).code())
                .containsExactly(McpEra.LEGACY, McpProtocol.INVALID_REQUEST);
    }

    @Test
    void aValidModernRequestIsServedWithItsProgressToken() {
        McpRequestMeta meta = new McpRequestMeta(Field.VALID, MODERN, Field.VALID, Field.VALID, McpProgressToken.of(7));
        assertThat(resolve("tools/list", meta, headers(List.of(MODERN), List.of("tools/list"), List.of())))
                .isEqualTo(new Serve(McpEra.MODERN, MODERN, McpProgressToken.of(7)));
    }

    @Test
    void aMetaVersionThatIsALegacyRevisionIsServedAsLegacyOnlyWhenTheHeaderAgrees() {
        McpRequestMeta legacyMeta = new McpRequestMeta(Field.VALID, LEGACY, Field.VALID, Field.ABSENT, null);
        assertThat(resolve("tools/list", legacyMeta, headers(List.of(LEGACY), List.of(), List.of())))
                .isEqualTo(new Serve(McpEra.LEGACY, LEGACY, null));
        assertThat(resolve("tools/list", legacyMeta, headers(List.of(MODERN), List.of("tools/list"), List.of())))
                .isEqualTo(modernRejection(
                        McpProtocol.HEADER_MISMATCH, McpProtocol.PROTOCOL_VERSION_HEADER_MISMATCH_MESSAGE));
    }

    @Test
    void modernValidationRunsInSpecificationOrder() {
        McpRequestHeaders good = headers(List.of(MODERN), List.of("tools/call"), List.of("get_overview"));
        assertThat(resolveCall(new McpRequestMeta(Field.INVALID, null, Field.ABSENT, Field.INVALID, null), good))
                .isEqualTo(modernRejection(McpProtocol.INVALID_PARAMS, McpProtocol.META_PROTOCOL_VERSION_TYPE_MESSAGE));
        for (List<String> versionHeader : List.of(List.<String>of(), List.of(MODERN, MODERN), List.of(LEGACY))) {
            assertThat(resolveCall(modernMeta(), headers(versionHeader, List.of(), List.of())))
                    .isEqualTo(modernRejection(
                            McpProtocol.HEADER_MISMATCH, McpProtocol.PROTOCOL_VERSION_HEADER_MISMATCH_MESSAGE));
        }
        McpRequestMeta unknown = new McpRequestMeta(Field.VALID, "2099-01-01", Field.ABSENT, Field.ABSENT, null);
        assertThat(resolveCall(unknown, headers(List.of("2099-01-01"), List.of(), List.of())))
                .isEqualTo(new Rejected(
                        McpEra.MODERN,
                        400,
                        McpProtocol.UNSUPPORTED_PROTOCOL_VERSION_ERROR,
                        "Unsupported protocol version",
                        List.of(MODERN, LEGACY),
                        "2099-01-01"));
        assertThat(resolveCall(new McpRequestMeta(Field.VALID, MODERN, Field.ABSENT, Field.ABSENT, null), good))
                .isEqualTo(modernRejection(
                        McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_META_CLIENT_CAPABILITIES_MESSAGE));
        assertThat(resolveCall(new McpRequestMeta(Field.VALID, MODERN, Field.INVALID, Field.ABSENT, null), good))
                .isEqualTo(
                        modernRejection(McpProtocol.INVALID_PARAMS, McpProtocol.META_CLIENT_CAPABILITIES_TYPE_MESSAGE));
        for (List<String> methodHeader :
                List.of(List.<String>of(), List.of("tools/list"), List.of("tools/call", "tools/call"))) {
            assertThat(resolveCall(modernMeta(), headers(List.of(MODERN), methodHeader, List.of("get_overview"))))
                    .isEqualTo(
                            modernRejection(McpProtocol.HEADER_MISMATCH, McpProtocol.METHOD_HEADER_MISMATCH_MESSAGE));
        }
        assertThat(resolveCall(new McpRequestMeta(Field.VALID, MODERN, Field.VALID, Field.INVALID, null), good))
                .isEqualTo(modernRejection(McpProtocol.INVALID_PARAMS, McpProtocol.PROGRESS_TOKEN_TYPE_MESSAGE));
    }

    @Test
    void theNameHeaderMustMirrorTheBodyNameAfterDecoding() {
        for (List<String> nameHeader : List.of(
                List.<String>of(),
                List.of("get_health"),
                List.of("get_overview", "get_overview"),
                List.of("=?base64?***?="))) {
            assertThat(resolveCall(modernMeta(), headers(List.of(MODERN), List.of("tools/call"), nameHeader)))
                    .isEqualTo(modernRejection(McpProtocol.HEADER_MISMATCH, McpProtocol.NAME_HEADER_MISMATCH_MESSAGE));
        }
        assertThat(resolveCall(
                        modernMeta(),
                        headers(List.of(MODERN), List.of("tools/call"), List.of("=?base64?Z2V0X292ZXJ2aWV3?="))))
                .isEqualTo(new Serve(McpEra.MODERN, MODERN, null));
        assertThat(McpEraResolver.resolve(
                        "prompts/get",
                        false,
                        null,
                        modernMeta(),
                        headers(List.of(MODERN), List.of("prompts/get"), List.of())))
                .as("a missing body name is the dispatcher's error to report")
                .isEqualTo(new Serve(McpEra.MODERN, MODERN, null));
        assertThat(McpEraResolver.resolve(
                        "tools/list",
                        false,
                        null,
                        modernMeta(),
                        headers(List.of(MODERN), List.of("tools/list"), List.of())))
                .as("only named methods need Mcp-Name")
                .isEqualTo(new Serve(McpEra.MODERN, MODERN, null));
    }

    @Test
    void notificationsAreAcceptedWithoutModernHeaders() {
        assertThat(McpEraResolver.resolve(
                        "notifications/initialized", true, null, modernMeta(), McpRequestHeaders.NONE))
                .isEqualTo(new Serve(McpEra.LEGACY, null, null));
        assertThat(McpEraResolver.resolve(
                        "notifications/initialized",
                        true,
                        null,
                        McpRequestMeta.NONE,
                        headers(List.of(MODERN), List.of(), List.of())))
                .isInstanceOf(Serve.class);
        assertThat(McpEraResolver.resolve(
                        "notifications/initialized",
                        true,
                        null,
                        McpRequestMeta.NONE,
                        headers(List.of("2099-01-01"), List.of(), List.of())))
                .isInstanceOf(Rejected.class);
    }

    @Test
    void wireCodesMoveOnlyBootUiServerCodesForModernClients() {
        assertThat(McpProtocol.wireErrorCode(McpEra.MODERN, McpProtocol.SERVER_DISABLED))
                .isEqualTo(-31000);
        assertThat(McpProtocol.wireErrorCode(McpEra.MODERN, McpProtocol.SERVER_AT_CAPACITY))
                .isEqualTo(-31001);
        assertThat(McpProtocol.wireErrorCode(McpEra.MODERN, McpProtocol.TOOL_TIMEOUT))
                .isEqualTo(-31002);
        assertThat(McpProtocol.wireErrorCode(McpEra.MODERN, McpProtocol.RESPONSE_TOO_LARGE))
                .isEqualTo(-31003);
        for (int code : List.of(-32700, -32600, -32601, -32602, -32603, -32020, -32022)) {
            assertThat(McpProtocol.wireErrorCode(McpEra.MODERN, code)).isEqualTo(code);
        }
        for (int code : List.of(-32000, -32001, -32002, -32003)) {
            assertThat(McpProtocol.wireErrorCode(McpEra.LEGACY, code)).isEqualTo(code);
        }
        assertThat(McpProtocol.errorHttpStatus(McpEra.MODERN, McpProtocol.METHOD_NOT_FOUND))
                .isEqualTo(404);
        assertThat(McpProtocol.errorHttpStatus(McpEra.LEGACY, McpProtocol.METHOD_NOT_FOUND))
                .isEqualTo(200);
        assertThat(McpProtocol.errorHttpStatus(McpEra.MODERN, McpProtocol.INVALID_PARAMS))
                .isEqualTo(200);
    }

    @Test
    void onlyAnExplicitEventStreamWithAPositiveQualityAllowsStreaming() {
        assertThat(McpProtocol.acceptsEventStream(List.of("application/json, text/event-stream")))
                .isTrue();
        assertThat(McpProtocol.acceptsEventStream(List.of("application/json", "TEXT/EVENT-STREAM;q=0.5")))
                .isTrue();
        assertThat(McpProtocol.acceptsEventStream(List.of("text/event-stream; charset=utf-8")))
                .isTrue();
        assertThat(McpProtocol.acceptsEventStream(null)).isFalse();
        assertThat(McpProtocol.acceptsEventStream(List.of())).isFalse();
        assertThat(McpProtocol.acceptsEventStream(List.of("*/*"))).isFalse();
        assertThat(McpProtocol.acceptsEventStream(List.of("text/*"))).isFalse();
        assertThat(McpProtocol.acceptsEventStream(List.of("application/json"))).isFalse();
        assertThat(McpProtocol.acceptsEventStream(List.of("text/event-stream;q=0")))
                .isFalse();
        assertThat(McpProtocol.acceptsEventStream(List.of("text/event-stream;q=abc")))
                .isFalse();
    }

    private static McpEraDecision resolve(String method, McpRequestMeta meta, McpRequestHeaders headers) {
        return McpEraResolver.resolve(method, false, null, meta, headers);
    }

    private static McpEraDecision resolveCall(McpRequestMeta meta, McpRequestHeaders headers) {
        return McpEraResolver.resolve("tools/call", false, "get_overview", meta, headers);
    }

    private static McpRequestMeta modernMeta() {
        return new McpRequestMeta(Field.VALID, MODERN, Field.VALID, Field.ABSENT, null);
    }

    private static McpRequestHeaders headers(List<String> version, List<String> method, List<String> name) {
        return new McpRequestHeaders(version, method, name);
    }

    private static Rejected modernRejection(int code, String message) {
        return new Rejected(McpEra.MODERN, 400, code, message, List.of(), null);
    }
}
