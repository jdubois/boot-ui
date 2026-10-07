package io.github.jdubois.bootui.engine.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolErrorReason;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CliOutcomesTests {

    @Test
    void successCarriesTheToolPayload() {
        CliToolResponse response = CliOutcomes.toResponse(new McpDispatchOutcome.ToolCallResult("payload"));

        assertThat(response.status()).isEqualTo(CliStatus.OK);
        assertThat(response.successful()).isTrue();
        assertThat(response.payload()).isEqualTo("payload");
        assertThat(response.error()).isNull();
    }

    @Test
    void aDisabledPanelIsForbiddenRatherThanReportedInBand() {
        CliToolResponse response = CliOutcomes.toResponse(
                new McpDispatchOutcome.ToolCallError("panel off", ToolErrorReason.PANEL_DISABLED));

        assertThat(response.status()).isEqualTo(CliStatus.FORBIDDEN);
        assertThat(response.error()).isEqualTo("panel off");
        assertThat(response.payload()).isNull();
    }

    @Test
    void anActionOnAReadOnlyPanelIsForbidden() {
        assertThat(CliOutcomes.toResponse(
                                new McpDispatchOutcome.ToolCallError("read only", ToolErrorReason.PANEL_READ_ONLY))
                        .status())
                .isEqualTo(CliStatus.FORBIDDEN);
    }

    @Test
    void anActionAlreadyRunningIsAConflict() {
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.ToolCallError("busy", ToolErrorReason.ACTION_BUSY))
                        .status())
                .isEqualTo(CliStatus.CONFLICT);
    }

    @Test
    void aPlainToolFailureIsAServerError() {
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.ToolCallError("boom"))
                        .status())
                .isEqualTo(CliStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void aClientErrorTheToolRaisedKeepsTheToolsOwnMessage() {
        // get_exception_detail on an unknown id is the caller's mistake, not a server fault. Reporting it as
        // 500 would tell a script to retry something that can never succeed. It is not reported as 404
        // either: on this facade that means "no such command", and the CLI would answer a wrong argument by
        // claiming the command does not exist.
        CliToolResponse response =
                CliOutcomes.toResponse(new McpDispatchOutcome.ToolCallError("exception unknown not found", 404));

        assertThat(response.status()).isEqualTo(CliStatus.BAD_REQUEST);
        assertThat(response.error()).isEqualTo("exception unknown not found");
    }

    @Test
    void aClientErrorStatusThisFacadeDoesNotModelStaysTheCallersFault() {
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.ToolCallError("unprocessable", 422))
                        .status())
                .isEqualTo(CliStatus.BAD_REQUEST);
    }

    @Test
    void aToolSuppliedStatusOutranksTheGenericFailureReason() {
        assertThat(CliOutcomes.toResponse(
                                new McpDispatchOutcome.ToolCallError("conflict", ToolErrorReason.TOOL_FAILED, 409))
                        .status())
                .isEqualTo(CliStatus.CONFLICT);
    }

    @Test
    void onlyAClientErrorStatusIsRepresentable() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CliStatus.forClientError(500))
                .withMessageContaining("500");
    }

    @Test
    void anUnknownToolIsNotFoundRatherThanABadRequest() {
        CliToolResponse response = CliOutcomes.toResponse(new McpDispatchOutcome.ProtocolError(
                McpProtocol.INVALID_PARAMS, McpProtocol.unknownToolMessage("no_such_tool")));

        assertThat(response.status()).isEqualTo(CliStatus.NOT_FOUND);
        assertThat(response.error()).contains("no_such_tool");
    }

    @Test
    void anUnavailableToolIsNotFoundSoEveryPublishedCliReadsItAsNotExposedHere() {
        CliToolResponse response = CliOutcomes.toResponse(new McpDispatchOutcome.ProtocolError(
                McpProtocol.INVALID_PARAMS,
                McpProtocol.unavailableToolMessage(
                        "get_kafka_activity", "Kafka", "No KafkaTemplate bean is available", Set.of()),
                Map.of("tool", "get_kafka_activity")));

        assertThat(response.status()).isEqualTo(CliStatus.NOT_FOUND);
        assertThat(response.error()).contains("No KafkaTemplate bean is available");
    }

    @Test
    void otherArgumentFailuresAreBadRequests() {
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.ProtocolError(
                                McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_ID_ARGUMENT_MESSAGE))
                        .status())
                .isEqualTo(CliStatus.BAD_REQUEST);
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.ProtocolError(
                                McpProtocol.INVALID_PARAMS, "Unexpected tool argument: query"))
                        .status())
                .isEqualTo(CliStatus.BAD_REQUEST);
    }

    @Test
    void capacityTimeoutAndDisabledMapToTheirTransportStatuses() {
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.ProtocolError(
                                McpProtocol.SERVER_AT_CAPACITY, McpProtocol.RATE_LIMITED_MESSAGE))
                        .status())
                .isEqualTo(CliStatus.TOO_MANY_REQUESTS);
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.ProtocolError(
                                McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE))
                        .status())
                .isEqualTo(CliStatus.GATEWAY_TIMEOUT);
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.ProtocolError(
                                McpProtocol.SERVER_DISABLED, McpProtocol.SERVER_DISABLED_MESSAGE))
                        .status())
                .isEqualTo(CliStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void unexpectedProtocolErrorsAndUnreachableVariantsAreServerErrors() {
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.ProtocolError(
                                McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE))
                        .status())
                .isEqualTo(CliStatus.INTERNAL_SERVER_ERROR);
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.ToolsListResult(List.of()))
                        .status())
                .isEqualTo(CliStatus.INTERNAL_SERVER_ERROR);
        assertThat(CliOutcomes.toResponse(new McpDispatchOutcome.NoResponse()).status())
                .isEqualTo(CliStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void theDisabledEndpointAnswersServiceUnavailableWithGuidance() {
        CliToolResponse response = CliOutcomes.disabled();

        assertThat(response.status()).isEqualTo(CliStatus.SERVICE_UNAVAILABLE);
        assertThat(response.error()).isEqualTo(CliOutcomes.DISABLED_MESSAGE);
        assertThat(response.error()).contains("bootui.cli.enabled");
    }

    @Test
    void aResponseNeverCarriesBothAPayloadAndAnError() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CliToolResponse(CliStatus.OK, "payload", "error"))
                .withMessageContaining("either a payload or an error");
    }

    @Test
    void statusesExposeTheirHttpCodes() {
        assertThat(CliStatus.OK.code()).isEqualTo(200);
        assertThat(CliStatus.BAD_REQUEST.code()).isEqualTo(400);
        assertThat(CliStatus.FORBIDDEN.code()).isEqualTo(403);
        assertThat(CliStatus.NOT_FOUND.code()).isEqualTo(404);
        assertThat(CliStatus.CONFLICT.code()).isEqualTo(409);
        assertThat(CliStatus.TOO_MANY_REQUESTS.code()).isEqualTo(429);
        assertThat(CliStatus.INTERNAL_SERVER_ERROR.code()).isEqualTo(500);
        assertThat(CliStatus.SERVICE_UNAVAILABLE.code()).isEqualTo(503);
        assertThat(CliStatus.GATEWAY_TIMEOUT.code()).isEqualTo(504);
    }
}
