package io.github.jdubois.bootui.quarkus.agent;

import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import org.eclipse.microprofile.config.Config;

/** Quarkus Claude Code session store; parsing/dashboard logic lives in the engine. */
public class QuarkusClaudeCodeSessionStore extends AgentSessionStore {

    public QuarkusClaudeCodeSessionStore(Config config) {
        super(new QuarkusClaudeCodeProperties(config), new QuarkusAgentJsonParser());
    }
}
