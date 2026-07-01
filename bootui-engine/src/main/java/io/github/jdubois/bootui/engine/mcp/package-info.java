/**
 * Framework-neutral, JSON-free core of the BootUI MCP (Model Context Protocol) server.
 *
 * <p>This package owns the transport-agnostic JSON-RPC 2.0 <em>dispatch</em> — method routing,
 * notification handling, per-panel gating (via the {@link io.github.jdubois.bootui.spi.McpPanelPolicy}
 * SPI), tool lookup, argument normalization / {@code max-results} capping, and the
 * {@code initialize}/{@code ping}/{@code tools/list}/{@code tools/call} outcomes — returning a sealed
 * {@link io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome}. It contains no Jackson or framework
 * types: Spring Boot 4 ships Jackson 3 ({@code tools.jackson.*}) and Quarkus ships Jackson 2
 * ({@code com.fasterxml.jackson.*}) — incompatible artifacts and packages — so each adapter keeps a
 * thin envelope codec that parses a request node into {@link io.github.jdubois.bootui.engine.mcp.McpRequest}
 * and renders the outcome back to JSON (echoing the id, serializing the tool payload with its own
 * {@code ObjectMapper}, and building each tool's input schema from
 * {@link io.github.jdubois.bootui.engine.mcp.McpToolSchema}). Each adapter also builds its own
 * {@link io.github.jdubois.bootui.engine.mcp.McpTool} catalog over its own controllers / resources and
 * holds its own live on/off state.
 */
package io.github.jdubois.bootui.engine.mcp;
