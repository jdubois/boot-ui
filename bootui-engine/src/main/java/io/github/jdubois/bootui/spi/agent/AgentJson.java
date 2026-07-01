package io.github.jdubois.bootui.spi.agent;

import java.util.List;
import java.util.Map;

/**
 * Framework- and library-neutral read-only view over a parsed JSON tree node.
 *
 * <p>The engine's agent-session parser reads local CLI session-state files through this abstraction so
 * the JSON-free engine never imports a Jackson type. Each adapter supplies a thin wrapper over its own
 * Jackson runtime (Jackson 3 / {@code tools.jackson} on Spring Boot, Jackson 2 / {@code
 * com.fasterxml.jackson} on Quarkus) via {@link AgentJsonParser}. Accessor semantics mirror Jackson's
 * {@code JsonNode}: {@link #get} returns {@code null} for an absent field/index, while a JSON
 * {@code null} value is a non-null node for which {@link #isNull()} is {@code true}.
 */
public interface AgentJson {

    /** The named child, or {@code null} when absent. */
    AgentJson get(String fieldName);

    /** The array element at {@code index}, or {@code null} when out of range. */
    AgentJson get(int index);

    boolean isNull();

    boolean isArray();

    boolean isObject();

    boolean isString();

    boolean isBoolean();

    /** Number of elements for an array node, otherwise 0. */
    int size();

    /** Whether this value node can be losslessly read as a {@code long}. */
    boolean canConvertToLong();

    /** The text value (scalar coerced to string), or {@code null} when not a scalar. */
    String asString();

    long asLong();

    boolean asBoolean();

    /** Object field entries in insertion order; empty for non-objects. */
    List<Map.Entry<String, AgentJson>> properties();

    /** Pretty-printed JSON for the raw-reveal endpoint. */
    String toPrettyString();
}
