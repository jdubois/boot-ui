package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One request's call tree, while this run keeps it ({@code docs/PLAN-v2.md} §5.14): its recent requests and each
 * route's slowest and latest failed exemplars.
 *
 * @param available whether the code-paths sensor records this run
 * @param unavailableReason why not, or {@code null}
 * @param requestId the request id asked for
 * @param found whether its tree is kept
 * @param route its route, or {@code null} when the journal no longer names it
 * @param assemblyOnly whether its handler only assembled its result: it ran on an event loop, returned a reactive or
 *     asynchronous result, or BootUI could not tell where its work ran
 * @param ownMillis its own time: the union of its threads' fragments
 * @param asyncMillis its asynchronous children's time, shown apart
 * @param cut whether a fragment was flushed with calls still open
 * @param droppedCalls calls recorded in no node
 * @param nodes its nodes, depth first with each parent's children slowest first
 * @param topMethods its methods with the most self time, at most five
 * @param limitations what the tree cannot see
 */
public record CodePathsRequestTreeReport(
        boolean available,
        String unavailableReason,
        String requestId,
        boolean found,
        String route,
        boolean assemblyOnly,
        double ownMillis,
        double asyncMillis,
        boolean cut,
        long droppedCalls,
        List<CodePathsNodeDto> nodes,
        List<CodePathsMethodTimeDto> topMethods,
        List<String> limitations) {

    public CodePathsRequestTreeReport {
        nodes = DtoCollections.immutableCopy(nodes);
        topMethods = DtoCollections.immutableCopy(topMethods);
        limitations = DtoCollections.immutableCopy(limitations);
    }

    /** The report without the sensor, or for a request whose tree is not kept. */
    public static CodePathsRequestTreeReport empty(boolean available, String reason, String requestId) {
        return new CodePathsRequestTreeReport(
                available, reason, requestId, false, null, false, 0.0, 0.0, false, 0L, List.of(), List.of(), List.of());
    }
}
