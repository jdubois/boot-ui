package io.github.jdubois.bootui.quarkus.agent;

import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import org.eclipse.microprofile.config.Config;

/** Quarkus Copilot CLI session store; parsing/dashboard logic lives in the engine. */
public class QuarkusCopilotSessionStore extends AgentSessionStore {

    public QuarkusCopilotSessionStore(Config config) {
        super(new QuarkusCopilotProperties(config), new QuarkusAgentJsonParser());
    }
}
