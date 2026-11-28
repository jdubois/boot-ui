package org.acme.nativeconsole;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-owned, in-process telemetry witness. Quarkus must retain it alongside BootUI's processor; it
 * exports nothing and its counter is independent of the BootUI store.
 */
@ApplicationScoped
public class HostSpanProcessor {

    private static final AtomicLong ENDED = new AtomicLong();

    public static long ended() {
        return ENDED.get();
    }

    @Produces
    @Singleton
    SpanProcessor processor() {
        return new SpanProcessor() {
            @Override
            public void onStart(Context parent, ReadWriteSpan span) {}

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan span) {
                if (span.getName().equals("native-acceptance-witness")) {
                    ENDED.incrementAndGet();
                }
            }

            @Override
            public boolean isEndRequired() {
                return true;
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode forceFlush() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }
}
