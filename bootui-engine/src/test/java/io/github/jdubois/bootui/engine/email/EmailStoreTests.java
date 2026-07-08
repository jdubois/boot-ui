package io.github.jdubois.bootui.engine.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmailStoreTests {

    @Test
    void capturesNewestFirst() {
        EmailStore store = new EmailStore(10);
        store.capture(email("first"), true);
        store.capture(email("second"), true);

        List<EmailStore.Entry> entries = store.list();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).email().subject()).isEqualTo("second");
        assertThat(entries.get(1).email().subject()).isEqualTo("first");
    }

    @Test
    void evictsOldestOnceFull() {
        EmailStore store = new EmailStore(2);
        store.capture(email("a"), true);
        store.capture(email("b"), true);
        store.capture(email("c"), true);

        List<EmailStore.Entry> entries = store.list();
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(e -> e.email().subject())).containsExactly("c", "b");
    }

    @Test
    void getReturnsCapturedEntryById() {
        EmailStore store = new EmailStore(10);
        EmailStore.Entry entry = store.capture(email("hello"), true);

        assertThat(store.get(entry.id())).isPresent();
        assertThat(store.get(entry.id()).get().email().subject()).isEqualTo("hello");
        assertThat(store.get("missing")).isEmpty();
    }

    @Test
    void clearDiscardsAllEntries() {
        EmailStore store = new EmailStore(10);
        store.capture(email("a"), true);
        store.clear();

        assertThat(store.list()).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void tracksSentFlag() {
        EmailStore store = new EmailStore(10);
        EmailStore.Entry entry = store.capture(email("trapped"), false);

        assertThat(entry.sent()).isFalse();
    }

    @Test
    void stampsTraceIdAndThreadAtCaptureTime() {
        EmailStore store = new EmailStore(10);
        store.setTraceIdProvider(() -> "trace-1");

        EmailStore.Entry entry = store.capture(email("traceable"), true);

        assertThat(entry.traceId()).isEqualTo("trace-1");
        assertThat(entry.thread()).isEqualTo(Thread.currentThread().getName());
    }

    private static CapturedEmail email(String subject) {
        return CapturedEmail.builder()
                .from("noreply@example.com")
                .to(List.of("user@example.com"))
                .subject(subject)
                .textBody("body")
                .build();
    }
}
