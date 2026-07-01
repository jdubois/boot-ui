package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.agent.QuarkusClaudeCodeSessionStore;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.Path;

/** Claude Code panel endpoints ({@code /bootui/api/claude-code}); shared logic in the engine store. */
@Path("/bootui/api/claude-code")
public class ClaudeCodeResource extends AbstractAgentSessionResource {

    @Inject
    public ClaudeCodeResource(
            @Named("claudeCode") QuarkusClaudeCodeSessionStore store, QuarkusExposurePolicy exposure) {
        super(store, exposure);
    }
}
