package io.github.jdubois.bootui.engine.mcp;

import java.util.Objects;

/** A reusable MCP prompt advertised by the BootUI server. */
public record McpPrompt(String name, String description, String text) {

    public McpPrompt {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(text, "text");
    }
}
