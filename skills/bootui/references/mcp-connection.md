# Connect an agent to the MCP server


Set this up only when the user asks to connect an agent or MCP client to BootUI. If BootUI MCP tools are already in
your tool list, just call them — there is nothing to configure. For a one-off diagnostic, use the CLI instead. The full
guide is at `https://github.com/jdubois/boot-ui/blob/main/docs/AI-AGENTS.md`.

The MCP server is opt-in. Enable it with `bootui.mcp.enabled=ON` or the MCP Server panel toggle, then configure the agent
with the application's actual port. VS Code uses a `servers` block:

```json
{
  "servers": {
    "bootui": {
      "type": "http",
      "url": "http://127.0.0.1:8080/bootui/api/mcp"
    }
  }
}
```

Claude Code, Cursor, and most other clients use `mcpServers` instead, and Claude Code can register the server directly
with `claude mcp add --transport http bootui http://127.0.0.1:8080/bootui/api/mcp`.

Prefer `127.0.0.1` and replace `8080` when needed. Loopback peers, configured trusted proxy ranges, and trusted container
gateways need no credentials. Any other peer allowed through `bootui.allow-non-localhost=true` must send BootUI's token as
`Authorization: Bearer <token>` on every call or receive `401`; the token is `bootui.authentication.token`, or the value
BootUI generated and logged once at startup. Verify
`GET /bootui/api/mcp-server` before debugging the client; it reports enabled state and advertised tools. Tools are
availability-driven, so do not assume every framework exposes every tool.

When BootUI MCP tools are available:

1. Establish context with `get_overview` and supplied/prior health evidence. `get_health` executes live indicators that
   may query databases or services; obtain approval when behavior is unknown or that work is outside scope.
2. Use targeted diagnostic reads such as `get_live_activity`, `get_request_profile` (a journal-first selection for one
   retained request or execution, with its journal timeline and optional HTTP-exchange `buffers` profile),
   `get_agent_status` (BootUI Java agent attachment/claim state), `get_exceptions`,
   `get_exception_detail`, `get_sql_traces`, `get_traces`, `get_log_tail`, `get_http_exchanges`, `get_http_routes` (per-route request
   counts, status classes, and p50/p95/p99 latency over the retained window), `get_hibernate_statistics`, and
   `get_websockets`. Their buffers are bounded: before
   concluding that a request, statement, or call never happened, check the `retention` object for evictions.
   For what a run did across requests, use `get_runtime_insights`, `get_runtime_insight`, `get_runtime_impact`, and
   `get_runtime_run_comparison`, as described in the two Runtime Insights workflows above.
3. Run only the advisor relevant to the task and within the approved scan scope, such as `architecture_scan`, `spring_scan`, `hibernate_scan`,
   `memory_scan`, `security_scan`, `pentest_scan`, or `rest_api_scan`. On Quarkus, `spring_scan`,
   `get_spring_report`, and `get_spring_rule_violations` keep their Spring names but run the Quarkus application
   advisor (QA-* rules).
4. Use identifiers returned by summary tools to request detail rather than repeatedly fetching broad result sets.
5. After making and testing a fix, rerun the same tool and compare results.

Read tools honor panel enablement. Scan tools also honor panel and global read-only settings. Results are masked and
paginated reads are capped by `bootui.mcp.max-results`. Large reads (SQL traces, startup, log tail, sessions, the
vulnerabilities report, activity, exchanges, configuration, beans, metrics, conditions, threads) return a short first page without `limit`;
when `page.hasMore` is true, narrow `--query` or raise `--limit` instead of assuming you saw everything. The
vulnerabilities report lists at most 5 advisories per dependency, without details: query an exact `group:artifact` for
all of one dependency's advisories, or an exact advisory id or alias to read it whole. Agent status
and Side Effects summarize each sensor; pass `--query <sensor id>` for its hooks. Log and exception messages have secret-like assignments and
authorization credentials masked, and are omitted under `bootui.expose-values=METADATA_ONLY`; a `get_log_tail` line
with `messageOmitted: true` had its message withheld, not an empty one.
