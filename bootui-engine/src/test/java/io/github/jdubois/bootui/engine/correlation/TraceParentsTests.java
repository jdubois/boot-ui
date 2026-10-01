package io.github.jdubois.bootui.engine.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TraceParentsTests {

    @Test
    void readsTheTraceIdOfAValidTraceparentInLowercase() {
        assertThat(TraceParents.traceIdOf("00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(TraceParents.traceIdOf(" 01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00-future "))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(TraceParents.traceIdOf((Object)
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void rejectsAbsentMalformedAndInvalidHeaders() {
        assertThat(TraceParents.traceIdOf((String) null)).isNull();
        assertThat(TraceParents.traceIdOf((Object) null)).isNull();
        assertThat(TraceParents.traceIdOf("not-a-traceparent")).isNull();
        assertThat(TraceParents.traceIdOf("00-4bf92f3577b34da6-00f067aa0ba902b7-01"))
                .isNull();
        assertThat(TraceParents.traceIdOf("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .isNull();
        assertThat(TraceParents.traceIdOf("00-00000000000000000000000000000000-00f067aa0ba902b7-01"))
                .isNull();
    }
}
