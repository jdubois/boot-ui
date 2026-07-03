package io.github.jdubois.bootui.engine.mcp;

/**
 * Normalized arguments passed to a tool handler.
 *
 * <p>The adapter extracts the raw {@code query}/{@code limit}/{@code id} from the parsed request; the
 * engine normalizes them once via {@link #normalize(String, Integer, String, int)} so the
 * {@code max-results} cap and the blank-string rules are applied identically on both adapters.
 *
 * @param query an optional case-insensitive filter (never blank; {@code null} when absent)
 * @param limit the effective page size: a client value floored at 1 and capped at {@code maxResults},
 *     or {@code maxResults} when the client supplied none
 * @param id an exact resource identifier for {@link McpToolSchema#ID} tools (never blank; {@code null}
 *     when absent, which {@link McpDispatcher} rejects before invoking the tool)
 */
public record McpArguments(String query, Integer limit, String id) {

    /**
     * Normalizes the raw, adapter-extracted arguments.
     *
     * @param rawQuery the client {@code query} as parsed (may be {@code null}/blank/untrimmed)
     * @param rawLimit the client {@code limit} as parsed (may be {@code null} or out of range)
     * @param rawId the client {@code id} as parsed (may be {@code null}/blank/untrimmed)
     * @param maxResults the configured {@code bootui.mcp.max-results} cap (already floored at 1)
     */
    public static McpArguments normalize(String rawQuery, Integer rawLimit, String rawId, int maxResults) {
        String query = (rawQuery == null) ? null : rawQuery.trim();
        if (query != null && query.isEmpty()) {
            query = null;
        }
        String id = (rawId == null) ? null : rawId.trim();
        if (id != null && id.isEmpty()) {
            id = null;
        }
        int limit = (rawLimit != null && rawLimit >= 1) ? Math.min(rawLimit, maxResults) : maxResults;
        return new McpArguments(query, limit, id);
    }
}
