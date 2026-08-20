package io.github.jdubois.bootui.spi;

/**
 * One aggregate gRPC call metric series, already read from the application's own instrumentation.
 *
 * <p>A sample is per service/method/status where the underlying series carries those dimensions. Missing
 * dimensions are {@code null} and the engine folds the sample into the coarsest level it can attribute
 * honestly rather than inventing a breakdown.</p>
 *
 * @param side whether the series was recorded on the server or client side
 * @param service the fully-qualified service name, or {@code null} when the series does not carry one
 * @param method the bare method name, or {@code null} when the series does not carry one
 * @param status the gRPC status code name, or {@code null} for series that do not break down by status
 * @param count number of completed calls in this series
 * @param totalDurationMs summed duration in milliseconds, or {@code null} for non-timing series
 * @param maxDurationMs longest observed duration in milliseconds, or {@code null} for non-timing series
 * @param activeCalls calls currently in progress, or {@code null} when the series is not an in-progress gauge
 */
public record GrpcCallMetricSample(
        GrpcCallSide side,
        String service,
        String method,
        String status,
        long count,
        Double totalDurationMs,
        Double maxDurationMs,
        Long activeCalls) {}
