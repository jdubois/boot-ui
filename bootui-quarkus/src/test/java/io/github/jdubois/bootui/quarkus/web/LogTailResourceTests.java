package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.StubConfig;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins that the Quarkus Log Tail resource reads the snapshot and every streamed line, backlog and live alike,
 * through the engine read path under the live exposure policy.
 */
class LogTailResourceTests {

    private final AtomicReference<ValueExposure> mode = new AtomicReference<>(ValueExposure.MASKED);

    private final AtomicBoolean maskSecrets = new AtomicBoolean(true);

    private final QuarkusExposurePolicy policy = new QuarkusExposurePolicy(StubConfig.empty()) {
        @Override
        public ValueExposure valueExposure() {
            return mode.get();
        }

        @Override
        public boolean maskSecrets() {
            return maskSecrets.get();
        }
    };

    @Test
    void recentAppliesTheLiveExposurePolicyToRetainedLines() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line("login password=hunter2\nthen token: tok-1"));
        LogTailResource resource = new LogTailResource(buffer, policy);

        assertThat(resource.recent().get(0).message()).isEqualTo("login password=******\nthen token: ******");

        mode.set(ValueExposure.METADATA_ONLY);
        assertThat(resource.recent())
                .containsExactly(new LogLineDto(7L, "WARN", "com.example.Db", null, "executor-1", true));

        mode.set(ValueExposure.FULL);
        assertThat(resource.recent().get(0).message()).isEqualTo("login password=hunter2\nthen token: tok-1");

        mode.set(ValueExposure.MASKED);
        maskSecrets.set(false);
        assertThat(resource.recent().get(0).message()).isEqualTo("login password=hunter2\nthen token: tok-1");
    }

    @Test
    void streamExposesBacklogAndLiveLinesUnderThePolicyInForceWhenEachIsEmitted() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line("backlog password=hunter2"));
        LogTailResource resource = new LogTailResource(buffer, policy);
        List<Object> emitted = new CopyOnWriteArrayList<>();

        AssertSubscriber<OutboundSseEvent> subscriber =
                resource.stream(sse(emitted)).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
        buffer.add(line("live api_key=ak-1"));
        mode.set(ValueExposure.METADATA_ONLY);
        buffer.add(line("omitted password=hunter2"));
        mode.set(ValueExposure.FULL);
        buffer.add(line("verbatim password=hunter2"));
        subscriber.cancel();

        assertThat(emitted)
                .extracting(data -> ((LogLineDto) data).message())
                .containsExactly("backlog password=******", "live api_key=******", null, "verbatim password=hunter2");
        assertThat(emitted)
                .extracting(data -> ((LogLineDto) data).messageOmitted())
                .containsExactly(false, false, true, false);
    }

    private static LogLineDto line(String message) {
        return new LogLineDto(7L, "WARN", "com.example.Db", message, "executor-1");
    }

    private static Sse sse(List<Object> emitted) {
        Sse sse = mock(Sse.class);
        OutboundSseEvent.Builder builder = mock(OutboundSseEvent.Builder.class, RETURNS_SELF);
        when(sse.newEventBuilder()).thenReturn(builder);
        when(builder.data(any(Object.class))).thenAnswer(invocation -> {
            emitted.add(invocation.getArgument(0));
            return builder;
        });
        when(builder.build()).thenReturn(mock(OutboundSseEvent.class));
        return sse;
    }
}
