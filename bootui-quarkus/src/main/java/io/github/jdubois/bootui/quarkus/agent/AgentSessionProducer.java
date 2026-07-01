package io.github.jdubois.bootui.quarkus.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

/**
 * Produces the shared engine agent session stores for the Quarkus Copilot and Claude Code panels. The
 * stores parse local CLI session-state files via {@link QuarkusAgentJsonParser} and watch for changes;
 * they are started unless the panel is explicitly OFF, mirroring the Spring factory, and stopped on
 * shutdown via {@code @Disposes} (the engine dropped {@code @PreDestroy} to stay framework-neutral).
 */
@ApplicationScoped
public class AgentSessionProducer {

    @Produces
    @Singleton
    @Named("copilot")
    public QuarkusCopilotSessionStore copilotStore(Config config) {
        QuarkusCopilotSessionStore store = new QuarkusCopilotSessionStore(config);
        if (store.isStartEnabled()) {
            store.start();
        }
        return store;
    }

    public void stopCopilot(@Disposes @Named("copilot") QuarkusCopilotSessionStore store) {
        store.stop();
    }

    @Produces
    @Singleton
    @Named("claudeCode")
    public QuarkusClaudeCodeSessionStore claudeCodeStore(Config config) {
        QuarkusClaudeCodeSessionStore store = new QuarkusClaudeCodeSessionStore(config);
        if (store.isStartEnabled()) {
            store.start();
        }
        return store;
    }

    public void stopClaudeCode(@Disposes @Named("claudeCode") QuarkusClaudeCodeSessionStore store) {
        store.stop();
    }
}
