package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JournalNetworkCaptureTests {

    private static final long T = 1_000_000L;

    @Test
    void aCallOfTheSameRequestToTheSameHostAndPortCapturesItsConnect() {
        JournalNetworkCapture capture = new JournalNetworkCapture(null, key -> null);
        capture.learn(rest("LocalHost:8080", "r1", null, T, 5));

        assertThat(capture.restClient("localhost", 8080, "r1", null, T - 60_000, T - 60_000))
                .isTrue();
        assertThat(capture.restClient("localhost", 8081, "r1", null, T, T))
                .as("another port")
                .isFalse();
        assertThat(capture.restClient("localhost", 8080, "r2", null, T, T))
                .as("another request at the same time")
                .isFalse();
    }

    @Test
    void anUnownedConnectIsCapturedByACallAtTheSameTimeWithASecondOfSlack() {
        JournalNetworkCapture capture = new JournalNetworkCapture(null, key -> null);
        capture.learn(rest("api.example.com", "r1", null, T, 200));

        assertThat(capture.restClient("api.example.com", 443, null, null, T + 900, T + 900))
                .isTrue();
        assertThat(capture.restClient("api.example.com", 80, null, null, T - 999, T - 999))
                .isTrue();
        assertThat(capture.restClient("api.example.com", 8443, null, null, T, T))
                .as("a port the call did not name is not the default one")
                .isFalse();
        assertThat(capture.restClient("api.example.com", 443, null, null, T + 5_000, T + 5_000))
                .isFalse();
    }

    @Test
    void aConnectToAConfiguredProxyIsCapturedByAnyCallOfItsOwner() {
        JournalNetworkCapture capture = new JournalNetworkCapture(
                null, Map.of("https.proxyHost", "proxy.corp", "https.proxyPort", "3128")::get);
        capture.learn(rest("api.example.com", "r1", null, T, 5));

        assertThat(capture.restClient("proxy.corp", 3128, "r1", null, T, T)).isTrue();
        assertThat(capture.restClient("proxy.corp", 3128, "r9", null, T, T)).isFalse();
    }

    @Test
    void aRefreshIndexesEveryCallRecordedSinceTheLastOneNotOnlyTheNewestEvent() throws InterruptedException {
        // The journal lists its events newest first: a Reactor Netty event loop's unowned connect to localhost was
        // never captured when other events followed the REST client call that made it.
        try (RuntimeJournal journal = new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start())) {
            JournalNetworkCapture capture = new JournalNetworkCapture(journal, key -> null);
            assertThat(journal.offer(rest("localhost:18731", "r1", null, T, 679)))
                    .isTrue();
            assertThat(journal.offer(rest(null, "r1", null, T + 700, 1))).isTrue();
            assertThat(journal.offer(rest(null, "r2", null, T + 800, 1))).isTrue();
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

            capture.refresh();

            assertThat(capture.restClient("localhost", 18731, null, null, T + 290, T + 291))
                    .as("an unowned event-loop connect during the call")
                    .isTrue();

            assertThat(journal.offer(rest("api.example.com:8443", "r3", null, T + 2_000, 5)))
                    .isTrue();
            assertThat(journal.offer(rest(null, "r3", null, T + 2_100, 1))).isTrue();
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

            capture.refresh();

            assertThat(capture.restClient("api.example.com", 8443, null, null, T + 2_001, T + 2_001))
                    .as("a call recorded since the last refresh, followed by another event")
                    .isTrue();
            assertThat(capture.restClient("localhost", 18731, "r1", null, T, T))
                    .as("the calls indexed before are kept")
                    .isTrue();
        }
    }

    @Test
    void authoritiesAndTargetsSplitIntoHostAndPort() {
        assertThat(JournalNetworkCapture.hostPort("user@Example.com:8443")).containsExactly("example.com", "8443");
        assertThat(JournalNetworkCapture.hostPort("[::1]:5432")).containsExactly("[::1]", "5432");
        assertThat(JournalNetworkCapture.hostPort("example.com")).containsExactly("example.com", null);
        assertThat(JournalNetworkCapture.hostPort("unix:/var/run/docker.sock")[0])
                .isEqualTo("unix");
        assertThat(JournalNetworkCapture.hostPort("")).isNull();
    }

    private static RuntimeEvent rest(String authority, String request, String execution, long start, long millis) {
        RuntimeEvent event = event(
                JournalSource.REST_CLIENT,
                new RestClientPayload("GET", authority, "/x", 200, "RestClient", false),
                request);
        return new RuntimeEvent(
                event.source(),
                start,
                millis * 1_000_000L,
                request,
                execution,
                null,
                "main",
                null,
                false,
                event.payload());
    }

    private static RuntimeEvent event(JournalSource source, RuntimeEventPayload payload, String request) {
        return new RuntimeEvent(source, T, 1_000_000L, request, null, null, "main", null, false, payload);
    }
}
