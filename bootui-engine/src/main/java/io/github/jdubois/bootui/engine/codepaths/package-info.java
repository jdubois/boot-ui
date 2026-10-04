/**
 * Code Paths ({@code docs/PLAN-v2.md} §5.14, M5-4a): the engine's side of the BootUI agent's {@code code-paths} sensor.
 * The agent builds per-thread fragments of a request's call tree of application bean methods and hands them over as
 * {@code long[]} blobs; here, over JDK types only, they are decoded ({@link CodePathFragment}), merged into one request
 * tree per request ({@link RequestTreeBuilder}, {@link RequestTree}) with the handler thread's fragments under the
 * request and each executor handoff's fragments as an asynchronous child, kept in a bounded store of recent requests and
 * slow or failed exemplars per route ({@link RequestTreeStore}), and aggregated per method to decide the sensor's
 * adaptive exclusion ({@link AdaptiveExclusion}). {@link CodePathsService} wires them to a claim's drainer. Route trees,
 * the panel, and the tools come with M5-4b.
 */
package io.github.jdubois.bootui.engine.codepaths;
