/**
 * Runtime Insights ({@code docs/PLAN-v2.md} §5.4, §5.5): projections of the runtime journal's retained events into
 * requests with their children, and the observations that read them. Every observation is a pure function of the
 * snapshot, names what it counted, and reports when it could not run instead of returning nothing.
 */
package io.github.jdubois.bootui.engine.insights;
