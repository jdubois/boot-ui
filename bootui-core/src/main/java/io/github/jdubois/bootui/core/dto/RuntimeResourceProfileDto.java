package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Runtime Insights' <b>Profile resources</b> session ({@code docs/PLAN-v2.md} §5.11, D17): an opt-in JFR recording the
 * developer starts, bounded by {@code bootui.resources.jfr.max-duration}, whose CPU and allocation samples are joined to
 * the request segment open on their thread, virtual threads included. CPU is counted in samples, never as a measured
 * time.
 *
 * @param state {@code IDLE} before any session, {@code RUNNING}, {@code COMPLETED}, {@code FAILED}, or {@code
 *     UNAVAILABLE} on a runtime without JFR
 * @param reason why it failed or is unavailable, or {@code null}
 * @param sampler the CPU sampler that ran, {@code jdk.CPUTimeSample} or {@code jdk.ExecutionSample}, or {@code null}
 * @param startedAt when the session started, in epoch milliseconds, or {@code null}
 * @param endsAt when it ends, or ended, on its own, or {@code null}
 * @param finishedAt when its samples were joined, or {@code null}
 * @param maxDurationSeconds the length of a session, {@code bootui.resources.jfr.max-duration}
 * @param cpuSamples every CPU sample of the last session
 * @param outsideSamples CPU samples taken while no request ran on their thread
 * @param requests the requests the samples were joined to
 * @param routes those requests by route, most CPU samples first, at most {@value #MAX_ROUTES}
 * @param routesOmitted the routes beyond them
 * @param limitations what the session cannot see
 */
public record RuntimeResourceProfileDto(
        String state,
        String reason,
        String sampler,
        Long startedAt,
        Long endsAt,
        Long finishedAt,
        long maxDurationSeconds,
        long cpuSamples,
        long outsideSamples,
        long requests,
        List<RuntimeResourceProfileRouteDto> routes,
        int routesOmitted,
        List<String> limitations) {

    /** The routes listed at most. */
    public static final int MAX_ROUTES = 20;

    public RuntimeResourceProfileDto {
        routes = DtoCollections.immutableCopy(routes);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
