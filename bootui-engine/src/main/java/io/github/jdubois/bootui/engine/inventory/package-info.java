/**
 * Code Inventory ({@code docs/PLAN-v2.md} §5.15, M5-3): which application methods ran in this run, which changed since
 * the previous run, and which dependencies loaded classes. The BootUI agent's {@code inventory} sensor only sets hit
 * flags and emits first-hit and class-load records; everything else is here, over JDK types: the records routed from the
 * claim's drainer ({@code javaagent.AgentRecordDrainer}), the class-file hasher and the disk scan of the application's own class files, the run history kept
 * across DevTools restarts and Quarkus live reloads, and the per-run service the panel, the API, the MCP tool, and the
 * {@code changed-code-not-executed} observation read.
 */
package io.github.jdubois.bootui.engine.inventory;
