package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.quarkus4.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus4.agent.QuarkusCopilotSessionStore;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

/** Copilot panel endpoints ({@code /bootui/api/copilot}); shared logic in the engine store. */
@Path("/bootui/api/copilot")
@Singleton
public class CopilotResource extends AbstractAgentSessionResource {

    @Inject
    public CopilotResource(@Named("copilot") QuarkusCopilotSessionStore store, QuarkusExposurePolicy exposure) {
        super(store, exposure);
    }
}
