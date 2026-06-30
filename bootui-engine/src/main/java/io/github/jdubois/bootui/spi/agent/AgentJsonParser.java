package io.github.jdubois.bootui.spi.agent;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Adapter-supplied JSON parser for agent session-state files, keeping the engine free of any Jackson
 * dependency. Spring Boot implements it over Jackson 3 ({@code tools.jackson}); Quarkus over Jackson 2
 * ({@code com.fasterxml.jackson}). Both must produce identical {@link AgentJson} accessor results so
 * the shared engine store renders the same payloads on either runtime.
 */
public interface AgentJsonParser {

    /** Parse a whole legacy {@code .json} session file into a tree. */
    AgentJson parseTree(Path file) throws IOException;

    /** Parse a single JSONL line; throws on malformed input (caller treats as schema drift). */
    AgentJson parseLine(String line);
}
