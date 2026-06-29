package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import java.time.Clock;

/**
 * Session store for sanitized local Copilot CLI session-state files.
 *
 * <p>All parsing and dashboard logic lives in {@link AgentSessionStore}; this
 * subclass exists so Spring can resolve the Copilot bean separately from
 * {@link ClaudeCodeSessionStore} and so the type carries the
 * {@code BootUiProperties.Copilot} configuration shape.</p>
 */
public class CopilotSessionStore extends AgentSessionStore {

    public CopilotSessionStore(BootUiProperties.Copilot properties) {
        super(properties, new SpringAgentJsonParser());
    }

    CopilotSessionStore(BootUiProperties.Copilot properties, Clock clock) {
        super(properties, new SpringAgentJsonParser(), clock);
    }
}
