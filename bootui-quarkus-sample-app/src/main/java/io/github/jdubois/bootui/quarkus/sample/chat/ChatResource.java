package io.github.jdubois.bootui.quarkus.sample.chat;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.logging.Logger;

@Path("/api/chat")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChatResource {

    private static final Logger LOG = Logger.getLogger(ChatResource.class);

    @Inject
    Instance<AiAssistant> assistant;

    @POST
    public Response chat(ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Message must not be blank."))
                    .build();
        }
        if (assistant.isUnsatisfied()) {
            return aiUnavailable();
        }
        try {
            String reply = assistant.get().chat(request.message().trim());
            return Response.ok(Map.of("reply", reply == null ? "" : reply)).build();
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "AI chat failed: %s", failure.getMessage());
            return aiUnavailable();
        }
    }

    private static Response aiUnavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                        "error",
                        "LangChain4j Ollama is not reachable. Start Ollama (or the docker profile) and pull the model."))
                .build();
    }

    public record ChatRequest(String message) {}
}
