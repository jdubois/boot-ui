package io.github.jdubois.bootui.engine.mcp;

/**
 * Normalized arguments passed to a tool handler.
 *
 * <p>The adapter extracts the raw {@code query}/{@code limit}/{@code id} from the parsed request; the
 * engine normalizes them once via {@link #normalize(String, Integer, String, int)} so the
 * {@code max-results} cap and the blank-string rules are applied identically on both adapters.
 *
 * @param query an optional case-insensitive filter (never blank; {@code null} when absent)
 * @param limit the effective page size, capped at {@code maxResults}; advisor pages default to 100 and
 *     additionally cap at 1000, while existing tools default to {@code maxResults}
 * @param id an exact resource identifier for {@link McpToolSchema#ID} tools (never blank; {@code null}
 *     when absent, which {@link McpDispatcher} rejects before invoking the tool)
 * @param scanId the completed snapshot identifier for advisor detail reads, otherwise {@code null}
 * @param offset the retained detail offset for advisor reads (defaults to zero), otherwise {@code null}
 */
public record McpArguments(String query, Integer limit, String id, String scanId, Integer offset) {

    /** Backward-compatible constructor for existing tools. */
    public McpArguments(String query, Integer limit, String id) {
        this(query, limit, id, null, null);
    }

    /** Applies advisor page defaults without changing any existing tool's default. */
    public static McpArguments normalize(McpRequest request, McpToolSchema schema, int maxResults) {
        return normalize(request, schema, maxResults, null);
    }

    /**
     * Applies advisor page defaults, and {@code defaultLimit} (capped at {@code maxResults}) when the call asks for no
     * {@code limit}; a {@code null} {@code defaultLimit} keeps {@code maxResults}.
     */
    public static McpArguments normalize(
            McpRequest request, McpToolSchema schema, int maxResults, Integer defaultLimit) {
        McpArguments base = normalize(request.rawQuery(), request.rawLimit(), request.rawId(), maxResults);
        if (defaultLimit != null && request.rawLimit() == null && schema != McpToolSchema.RULE_VIOLATIONS) {
            return new McpArguments(base.query(), Math.min(Math.max(1, defaultLimit), maxResults), base.id());
        }
        if (schema != McpToolSchema.RULE_VIOLATIONS) {
            return base;
        }
        String scanId = request.rawScanId() == null ? null : request.rawScanId().trim();
        if (scanId != null && scanId.isEmpty()) {
            scanId = null;
        }
        int limit = Math.min(request.rawLimit() == null ? 100 : request.rawLimit(), Math.min(1000, maxResults));
        return new McpArguments(
                base.query(), limit, base.id(), scanId, request.rawOffset() == null ? 0 : request.rawOffset());
    }

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
