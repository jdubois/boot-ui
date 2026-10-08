package io.github.jdubois.bootui.engine.mcp;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The canonical form of a JSON-RPC request id, so a {@code notifications/cancelled} that names {@code 7} or {@code
 * 7.0} finds the request sent with id {@code 7}, and the string {@code "7"} never does.
 */
public final class McpRequestKey {

    private McpRequestKey() {}

    /** The key of a string id. */
    public static String text(String id) {
        return "s:" + Objects.requireNonNull(id, "id");
    }

    /**
     * The key of a numeric id, compared by value. Scientific notation keeps the key short whatever the exponent (an id
     * like {@code 1e999999999} would otherwise expand to a gigabyte), and stays one string per value.
     */
    public static String number(BigDecimal id) {
        BigDecimal value = Objects.requireNonNull(id, "id");
        return "n:" + (value.signum() == 0 ? "0" : value.stripTrailingZeros().toString());
    }
}
