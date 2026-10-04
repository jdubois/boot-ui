package io.github.jdubois.bootui.engine.mcp;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The shape of an MCP tool's input schema, advertised in {@code tools/list}.
 *
 * <p>The adapter renders the concrete JSON Schema from this enum so the engine stays JSON-free. The
 * kinds cover every BootUI tool: action and plain read tools take no arguments ({@link #NONE}),
 * paged reads accept an optional {@code limit} ({@link #LIMIT}), searchable reads accept an
 * optional {@code query} plus {@code limit} ({@link #QUERY_LIMIT}), and single-resource reads require
 * an exact {@code id} ({@link #ID}). Advisor detail reads additionally require a snapshot identifier
 * and accept an offset ({@link #RULE_VIOLATIONS}).
 */
public enum McpToolSchema {
    /** No arguments: an empty object schema. */
    NONE(List.of()),
    /** An optional positive integer {@code limit}, capped by {@code bootui.mcp.max-results}. */
    LIMIT(List.of("limit")),
    /** An optional {@code query} string plus the optional {@code limit}. */
    QUERY_LIMIT(List.of("query", "limit")),
    /** A required string {@code id} identifying one specific resource (e.g. an exception group id). */
    ID(List.of("id")),
    /** An optional resource id; omission selects the tool's documented default. */
    OPTIONAL_ID(List.of("id")),
    /** A required rule {@code id} and {@code scanId}, with optional {@code offset} and {@code limit}. */
    RULE_VIOLATIONS(List.of("id", "scanId", "offset", "limit"));

    private final Set<String> argumentNames;

    McpToolSchema(List<String> argumentNames) {
        this.argumentNames = Collections.unmodifiableSet(new LinkedHashSet<>(argumentNames));
    }

    /**
     * Argument names accepted by this schema, in declaration order.
     *
     * <p>Iteration order is stable across JVM runs. {@code Set.of(...)} would not be: its iteration order is
     * salted per JVM, and the command-line facade publishes this list as the argument order a generated CLI
     * binds flags and positionals from, so a shuffled order would move arguments between runs.
     */
    public Set<String> argumentNames() {
        return argumentNames;
    }
}
