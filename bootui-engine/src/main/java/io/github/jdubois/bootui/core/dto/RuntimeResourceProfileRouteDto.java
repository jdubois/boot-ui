package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One route's share of a <b>Profile resources</b> session ({@code docs/PLAN-v2.md} §5.11).
 *
 * @param route the route, such as {@code GET /api/orders/{id}}, or {@code Requests no longer retained}
 * @param requests its requests the samples were joined to
 * @param cpuSamples the CPU samples taken while they ran
 * @param allocatedBytes JFR's estimate of the bytes they allocated, from its allocation samples
 * @param virtualThreads whether any sample was taken on a virtual thread, which scope readings cannot measure
 * @param hotFrames the frames most often on CPU, each sample's first application frame, at most {@value #MAX_FRAMES}
 */
public record RuntimeResourceProfileRouteDto(
        String route,
        long requests,
        long cpuSamples,
        long allocatedBytes,
        boolean virtualThreads,
        List<RuntimeResourceProfileFrameDto> hotFrames) {

    /** The hot frames listed at most. */
    public static final int MAX_FRAMES = 5;

    public RuntimeResourceProfileRouteDto {
        hotFrames = DtoCollections.immutableCopy(hotFrames);
    }
}
