package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import java.time.Clock;

/**
 * Session store for Claude Code JSONL project logs.
 *
 * <p>All parsing and dashboard logic lives in {@link AgentSessionStore}; this
 * subclass exists so Spring can resolve the Claude Code bean separately from
 * {@link CopilotSessionStore} and so the type carries the
 * {@code BootUiProperties.ClaudeCode} configuration shape.</p>
 */
public class ClaudeCodeSessionStore extends AgentSessionStore {

    public ClaudeCodeSessionStore(BootUiProperties.ClaudeCode properties) {
        super(properties, new SpringAgentJsonParser());
    }

    ClaudeCodeSessionStore(BootUiProperties.ClaudeCode properties, Clock clock) {
        super(properties, new SpringAgentJsonParser(), clock);
    }
}
