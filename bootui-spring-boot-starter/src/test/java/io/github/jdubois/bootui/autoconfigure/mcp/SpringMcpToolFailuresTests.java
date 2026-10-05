package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SpringMcpToolFailuresTests {

    private static final McpArguments ARGUMENTS = McpArguments.normalize(null, null, "does-not-exist", 50);

    @Test
    void clientErrorBecomesCanonicalToolClientException() {
        Function<McpArguments, Object> handler = SpringMcpToolFailures.translating(args -> {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "exception " + args.id() + " not found");
        });

        assertThatThrownBy(() -> handler.apply(ARGUMENTS))
                .isInstanceOf(McpToolClientException.class)
                .hasMessage("exception does-not-exist not found")
                .extracting(failure -> ((McpToolClientException) failure).status())
                .isEqualTo(404);
    }

    @Test
    void otherClientStatusesAreTranslatedToo() {
        Function<McpArguments, Object> conflict = SpringMcpToolFailures.translating(args -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Service does not support restart");
        });

        assertThatThrownBy(() -> conflict.apply(ARGUMENTS))
                .isInstanceOf(McpToolClientException.class)
                .extracting(failure -> ((McpToolClientException) failure).status())
                .isEqualTo(409);
    }

    @Test
    void missingReasonFallsBackToProblemDetailThenCanonicalMessage() {
        Function<McpArguments, Object> handler = SpringMcpToolFailures.translating(args -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        });

        assertThatThrownBy(() -> handler.apply(ARGUMENTS))
                .isInstanceOf(McpToolClientException.class)
                .satisfies(failure ->
                        assertThat(failure.getMessage()).isIn("Bad Request", McpProtocol.TOOL_CALL_FAILED_MESSAGE));
    }

    @Test
    void serverErrorIsRethrownSoItStaysAnInternalError() {
        ResponseStatusException failure =
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read service logs");
        Function<McpArguments, Object> handler = SpringMcpToolFailures.translating(args -> {
            throw failure;
        });

        assertThatThrownBy(() -> handler.apply(ARGUMENTS)).isSameAs(failure);
    }

    @Test
    void unrelatedRuntimeFailureIsRethrownUnchanged() {
        IllegalStateException failure = new IllegalStateException("boom");
        Function<McpArguments, Object> handler = SpringMcpToolFailures.translating(args -> {
            throw failure;
        });

        assertThatThrownBy(() -> handler.apply(ARGUMENTS)).isSameAs(failure);
    }

    @Test
    void successfulHandlerIsPassedThrough() {
        Function<McpArguments, Object> handler = SpringMcpToolFailures.translating(args -> Map.of("id", args.id()));

        assertThat(handler.apply(ARGUMENTS)).isEqualTo(Map.of("id", "does-not-exist"));
    }
}
