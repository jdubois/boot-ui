package io.opentelemetry.exporter.fake;

import java.util.function.Supplier;

/** Stands for an OpenTelemetry exporter, whose transport is an HTTP client. */
public final class FakeExporter {

    private FakeExporter() {}

    public static long[] export(Supplier<long[]> call) {
        return call.get();
    }
}
