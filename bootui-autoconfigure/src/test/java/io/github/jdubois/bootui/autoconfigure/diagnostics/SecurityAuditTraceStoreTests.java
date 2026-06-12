package io.github.jdubois.bootui.autoconfigure.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.diagnostics.SecurityAuditTraceStore.Captured;
import org.junit.jupiter.api.Test;

class SecurityAuditTraceStoreTests {

    @Test
    void recordsAndLooksUpCapturedContextByEventIdentity() {
        SecurityAuditTraceStore store = new SecurityAuditTraceStore();

        store.record(
                "2024-01-01T00:00:00Z", "AUTHENTICATION_FAILURE", "alice", new Captured("trace-1", "POST", "/login"));

        Captured captured = store.lookup("2024-01-01T00:00:00Z", "AUTHENTICATION_FAILURE", "alice");
        assertThat(captured).isNotNull();
        assertThat(captured.traceId()).isEqualTo("trace-1");
        assertThat(captured.requestMethod()).isEqualTo("POST");
        assertThat(captured.requestPath()).isEqualTo("/login");
    }

    @Test
    void returnsNullForUnknownEvent() {
        SecurityAuditTraceStore store = new SecurityAuditTraceStore();
        assertThat(store.lookup("2024-01-01T00:00:00Z", "AUTHENTICATION_FAILURE", "alice"))
                .isNull();
    }

    @Test
    void ignoresEntriesWithNoUsefulContext() {
        SecurityAuditTraceStore store = new SecurityAuditTraceStore();

        store.record("2024-01-01T00:00:00Z", "AUTHORIZATION_DENIED", "bob", new Captured(null, null, null));

        assertThat(store.size()).isZero();
        assertThat(store.lookup("2024-01-01T00:00:00Z", "AUTHORIZATION_DENIED", "bob"))
                .isNull();
    }

    @Test
    void handlesNullEventCoordinates() {
        SecurityAuditTraceStore store = new SecurityAuditTraceStore();

        store.record(null, null, null, new Captured("trace-x", null, null));

        assertThat(store.lookup(null, null, null)).isNotNull();
        assertThat(store.lookup(null, null, null).traceId()).isEqualTo("trace-x");
    }

    @Test
    void evictsOldestEntriesBeyondCapacity() {
        SecurityAuditTraceStore store = new SecurityAuditTraceStore(2);

        store.record("t1", "TYPE", "u1", new Captured("trace-1", null, null));
        store.record("t2", "TYPE", "u2", new Captured("trace-2", null, null));
        store.record("t3", "TYPE", "u3", new Captured("trace-3", null, null));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.lookup("t1", "TYPE", "u1")).isNull();
        assertThat(store.lookup("t2", "TYPE", "u2")).isNotNull();
        assertThat(store.lookup("t3", "TYPE", "u3")).isNotNull();
    }
}
