package io.opentelemetry.instrumentation.fake;

import java.util.function.Supplier;

/** Stands for OpenTelemetry's instrumentation around an application's call, as its RestTemplate interceptor. */
public final class FakeInterceptor {

    private FakeInterceptor() {}

    public static long[] intercept(Supplier<long[]> call) {
        return call.get();
    }
}
