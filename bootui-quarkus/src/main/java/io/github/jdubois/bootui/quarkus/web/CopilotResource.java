package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.agent.QuarkusCopilotSessionStore;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.Path;

/** Copilot panel endpoints ({@code /bootui/api/copilot}); shared logic in the engine store. */
@Path("/bootui/api/copilot")
public class CopilotResource extends AbstractAgentSessionResource {

    @Inject
    public CopilotResource(@Named("copilot") QuarkusCopilotSessionStore store, QuarkusExposurePolicy exposure) {
        super(store, exposure);
    }
}
