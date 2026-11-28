package io.github.jdubois.bootui.quarkus4.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class QuarkusMcpToolFailuresTest {

    private static final McpArguments ARGUMENTS = McpArguments.normalize(null, null, "does-not-exist", 50);

    @Test
    void notFoundBecomesCanonicalToolClientException() {
        Function<McpArguments, Object> handler = QuarkusMcpToolFailures.translating(args -> {
            throw new NotFoundException("exception " + args.id() + " not found");
        });

        assertThatThrownBy(() -> handler.apply(ARGUMENTS))
                .isInstanceOf(McpToolClientException.class)
                .hasMessage("exception does-not-exist not found")
                .extracting(failure -> ((McpToolClientException) failure).status())
                .isEqualTo(404);
    }

    @Test
    void otherClientStatusesAreTranslatedToo() {
        Function<McpArguments, Object> handler = QuarkusMcpToolFailures.translating(args -> {
            throw new WebApplicationException("Logs are not available for this service", Response.Status.CONFLICT);
        });

        assertThatThrownBy(() -> handler.apply(ARGUMENTS))
                .isInstanceOf(McpToolClientException.class)
                .extracting(failure -> ((McpToolClientException) failure).status())
                .isEqualTo(409);
    }

    @Test
    void serverErrorIsRethrownSoItStaysAnInternalError() {
        WebApplicationException failure =
                new WebApplicationException("Unable to read service logs", Response.Status.INTERNAL_SERVER_ERROR);
        Function<McpArguments, Object> handler = QuarkusMcpToolFailures.translating(args -> {
            throw failure;
        });

        assertThatThrownBy(() -> handler.apply(ARGUMENTS)).isSameAs(failure);
    }

    @Test
    void unrelatedRuntimeFailureIsRethrownUnchanged() {
        IllegalStateException failure = new IllegalStateException("boom");
        Function<McpArguments, Object> handler = QuarkusMcpToolFailures.translating(args -> {
            throw failure;
        });

        assertThatThrownBy(() -> handler.apply(ARGUMENTS)).isSameAs(failure);
    }

    @Test
    void successfulHandlerIsPassedThrough() {
        Function<McpArguments, Object> handler = QuarkusMcpToolFailures.translating(args -> Map.of("id", args.id()));

        assertThat(handler.apply(ARGUMENTS)).isEqualTo(Map.of("id", "does-not-exist"));
    }
}
