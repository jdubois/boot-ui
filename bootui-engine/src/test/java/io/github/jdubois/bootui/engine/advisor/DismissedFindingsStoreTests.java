package io.github.jdubois.bootui.engine.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.DismissedFindingDto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DismissedFindingsStoreTests {

    private static final String RULE = "HIB-ID-006";
    private static final String IDENTITY = "v1:unit=app|entity=com.acme.Item|member=id";
    private static final String OTHER = "v1:unit=app|entity=com.acme.Order|member=customer";

    @Test
    void aLegacyFileWithoutTheSectionLoadsUnchanged(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boot-ui.yml");
        Files.writeString(file, "dismissedRules:\n  - SPRING-001\n");
        DismissedRulesStore store = new DismissedRulesStore(file);

        assertThat(store.load()).containsExactly("SPRING-001");
        assertThat(store.loadFindings().findings()).isEmpty();
        assertThat(store.loadFindings().ignored()).isZero();
    }

    @Test
    void dismissingAFindingKeepsTheRuleListByteForByteAndRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boot-ui.yml");
        Files.writeString(file, "# My config\nsomeSetting: value\ndismissedRules:\n  - SPRING-001\n  - A/B C\n");
        DismissedRulesStore store = new DismissedRulesStore(file);

        store.dismissFinding(RULE, IDENTITY, null);
        store.dismissFinding("HIB-MAP-006", OTHER, "Intentional");

        assertThat(Files.readString(file))
                .isEqualTo("# BootUI configuration (developer-local; safe to delete).\n\n"
                        + "# My config\nsomeSetting: value\n\n"
                        + "dismissedRules:\n  - SPRING-001\n  - A/B C\n"
                        + "dismissedFindings:\n  - " + RULE + " " + IDENTITY + "\n"
                        + "  - HIB-MAP-006 " + OTHER + " n=SW50ZW50aW9uYWw\n");
        DismissedRulesStore reread = new DismissedRulesStore(file);
        assertThat(reread.load()).containsExactly("SPRING-001", "A/B C");
        assertThat(reread.loadFindings().findings())
                .containsExactly(
                        new DismissedFindingDto(RULE, IDENTITY, null),
                        new DismissedFindingDto("HIB-MAP-006", OTHER, "Intentional"));
    }

    @Test
    void repeatedWritesAreByteStableAndLeaveNoEmptyRulesNode(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boot-ui.yml");
        DismissedRulesStore store = new DismissedRulesStore(file);
        store.dismissFinding(RULE, IDENTITY, null);
        String first = Files.readString(file);

        store.dismissFinding("HIB-MAP-006", OTHER, null);
        store.restoreFinding("HIB-MAP-006", OTHER);
        store.dismissFinding(RULE, IDENTITY, null);

        assertThat(Files.readString(file)).isEqualTo(first).doesNotContain("dismissedRules");
        store.restoreFinding(RULE, IDENTITY);
        assertThat(Files.readString(file)).doesNotContain("dismissedFindings").doesNotContain("dismissedRules");
    }

    @Test
    void aRuleWriteKeepsTheFindingsSectionAndAFindingWriteKeepsTheRules(@TempDir Path dir) {
        Path file = dir.resolve("boot-ui.yml");
        DismissedRulesStore store = new DismissedRulesStore(file);
        store.dismissFinding(RULE, IDENTITY, "note");

        store.dismiss("SPRING-001");
        store.restore("SPRING-001");

        assertThat(new DismissedRulesStore(file).loadFindings().findings())
                .containsExactly(new DismissedFindingDto(RULE, IDENTITY, "note"));
        store.dismiss("MEM-001");
        store.dismissFinding("HIB-MAP-006", OTHER, null);
        assertThat(new DismissedRulesStore(file).load()).containsExactly("MEM-001");
    }

    @Test
    void anOlderWriterThatOnlyKnowsDismissedRulesKeepsTheNewSection(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boot-ui.yml");
        new DismissedRulesStore(file).dismissFinding(RULE, IDENTITY, "kept");

        olderWriterDismiss(file, "SPRING-001");

        assertThat(Files.readString(file)).contains("dismissedFindings:\n  - " + RULE + " " + IDENTITY + " n=");
        DismissedRulesStore store = new DismissedRulesStore(file);
        assertThat(store.load()).containsExactly("SPRING-001");
        assertThat(store.loadFindings().findings()).containsExactly(new DismissedFindingDto(RULE, IDENTITY, "kept"));
    }

    @Test
    void notesWithQuotesColonsHashesNewlinesAndNonAsciiRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boot-ui.yml");
        DismissedRulesStore store = new DismissedRulesStore(file);
        String note = "it's \"fine\": # really\nsecond line \u00e9\u00e8 \ud83d\ude00 \\ end";

        store.dismissFinding(RULE, IDENTITY, note);

        assertThat(Files.readString(file)).doesNotContain("really").doesNotContain("fine");
        assertThat(new DismissedRulesStore(file).loadFindings().findings())
                .containsExactly(new DismissedFindingDto(RULE, IDENTITY, note));
    }

    @Test
    void aNoteIsReplacedAndABlankNoteMeansNone(@TempDir Path dir) {
        DismissedRulesStore store = new DismissedRulesStore(dir.resolve("boot-ui.yml"));
        store.dismissFinding(RULE, IDENTITY, "first");
        store.dismissFinding(RULE, IDENTITY, "second");

        assertThat(store.loadFindings().findings()).containsExactly(new DismissedFindingDto(RULE, IDENTITY, "second"));

        store.dismissFinding(RULE, IDENTITY, "   ");
        assertThat(store.loadFindings().findings()).containsExactly(new DismissedFindingDto(RULE, IDENTITY, null));
    }

    @Test
    void invalidInputIsRefusedWithoutEchoingIt(@TempDir Path dir) {
        DismissedRulesStore store = new DismissedRulesStore(dir.resolve("boot-ui.yml"));
        String secret = "secret-" + "x".repeat(3);

        for (String[] invalid : new String[][] {
            {"hib-id-006", IDENTITY},
            {RULE + "\n  - X-001", IDENTITY},
            {"HIB-ID-006", secret},
            {"HIB-ID-006", "v1:unit=app entity"},
            {"HIB-ID-006", "unit=app"},
            {"HIB-ID-006", "v1:" + "a".repeat(201)},
            {null, IDENTITY},
            {RULE, null}
        }) {
            assertThatThrownBy(() -> store.dismissFinding(invalid[0], invalid[1], null))
                    .isInstanceOfSatisfying(AdvisorViolationException.class, e -> {
                        assertThat(e.status()).isEqualTo(400);
                        assertThat(e.getMessage()).doesNotContain(secret);
                    });
        }
        assertThat(store.loadFindings().findings()).isEmpty();
    }

    @Test
    void aTooLongNoteAnUnpairedSurrogateAndALineBreakInARuleIdAreRefused(@TempDir Path dir) {
        DismissedRulesStore store = new DismissedRulesStore(dir.resolve("boot-ui.yml"));

        assertThatThrownBy(() -> store.dismissFinding(RULE, IDENTITY, "x".repeat(201)))
                .isInstanceOfSatisfying(
                        AdvisorViolationException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> store.dismissFinding(RULE, IDENTITY, "bad \ud800 text"))
                .isInstanceOfSatisfying(
                        AdvisorViolationException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> store.dismiss("SPRING-001\ndismissedFindings:"))
                .isInstanceOf(AdvisorViolationException.class);
        store.dismissFinding(RULE, IDENTITY, "x".repeat(200));
        assertThat(store.loadFindings().findings()).hasSize(1);
    }

    @Test
    void malformedEntriesAreIgnoredCountedAndKeptVerbatimOnRewrite(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boot-ui.yml");
        String goodNote = Base64.getUrlEncoder().withoutPadding().encodeToString("ok".getBytes(StandardCharsets.UTF_8));
        String paddedNote = Base64.getUrlEncoder().encodeToString("ok??".getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                file,
                "dismissedFindings:\n"
                        + "  - " + RULE + " " + IDENTITY + " n=" + goodNote + "\n"
                        + "  - " + RULE + " " + OTHER + " n=!!!\n"
                        + "  - " + RULE + " " + OTHER + " n=" + paddedNote + "\n"
                        + "  - " + RULE + "  " + OTHER + "\n"
                        + "  - lowercase-rule " + OTHER + "\n"
                        + "  - " + RULE + " v1:unit=app|member=it has spaces\n"
                        + "  - " + RULE + " " + OTHER + " extra=1\n"
                        + "  - " + RULE + " " + OTHER + " n=" + goodNote + " trailing\n"
                        + "  nested: future\n"
                        + "  - " + RULE + " " + OTHER + "\n");
        DismissedRulesStore store = new DismissedRulesStore(file);

        DismissedRulesStore.Findings loaded = store.loadFindings();
        assertThat(loaded.findings())
                .containsExactly(
                        new DismissedFindingDto(RULE, IDENTITY, "ok"), new DismissedFindingDto(RULE, OTHER, null));
        assertThat(loaded.ignored()).isEqualTo(8);

        store.dismiss("SPRING-001");

        String written = Files.readString(file);
        assertThat(written).contains("n=!!!").contains("extra=1").contains("nested: future");
        assertThat(store.loadFindings().ignored()).isEqualTo(8);
        assertThat(store.loadFindings().findings()).hasSize(2);
    }

    @Test
    void theStoreKeepsAtMostAThousandFindingsButStillReplacesANote(@TempDir Path dir) {
        DismissedRulesStore store = new DismissedRulesStore(dir.resolve("boot-ui.yml"));
        for (int i = 0; i < DismissedRulesStore.MAX_FINDINGS; i++) {
            store.dismissFinding(RULE, "v1:unit=app|member=m" + i, null);
        }

        assertThatThrownBy(() -> store.dismissFinding(RULE, "v1:unit=app|member=overflow", null))
                .isInstanceOfSatisfying(
                        AdvisorViolationException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
        store.dismissFinding(RULE, "v1:unit=app|member=m7", "still editable");
        assertThat(store.loadFindings().findings()).hasSize(DismissedRulesStore.MAX_FINDINGS);
    }

    @Test
    void concurrentWritersNeverLoseAnEntry(@TempDir Path dir) throws Exception {
        DismissedRulesStore store = new DismissedRulesStore(dir.resolve("boot-ui.yml"));
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                int id = writer;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        store.dismissFinding(RULE, "v1:unit=app|member=w" + id + "i" + i, "n" + i);
                        store.dismiss("RULE-" + id + "-" + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        DismissedRulesStore reread = new DismissedRulesStore(dir.resolve("boot-ui.yml"));
        assertThat(reread.loadFindings().findings()).hasSize(80);
        assertThat(reread.load()).hasSize(80);
    }

    @Test
    void theSnapshotReadsRulesAndFindingsTogether(@TempDir Path dir) {
        DismissedRulesStore store = new DismissedRulesStore(dir.resolve("boot-ui.yml"));
        store.dismiss("SPRING-001");
        store.dismissFinding(RULE, IDENTITY, "n");

        DismissedRulesStore.Snapshot snapshot = store.snapshot();

        assertThat(snapshot.rules()).containsExactly("SPRING-001");
        assertThat(snapshot.findings()).containsExactly(new DismissedFindingDto(RULE, IDENTITY, "n"));
        assertThat(snapshot.ignored()).isZero();
    }

    /** What the store did before the findings section existed: manage only {@code dismissedRules}. */
    private static void olderWriterDismiss(Path file, String ruleId) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder("# BootUI configuration (developer-local; safe to delete).\n");
        boolean inSection = false;
        for (String line : lines) {
            if (line.equals("# BootUI configuration (developer-local; safe to delete).")) continue;
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0)) && line.contains(":")) {
                inSection = line.substring(0, line.indexOf(':')).trim().equals("dismissedRules");
                if (!inSection) out.append(line).append('\n');
                continue;
            }
            if (!inSection) out.append(line).append('\n');
        }
        out.append('\n').append("dismissedRules:\n  - ").append(ruleId).append('\n');
        Files.writeString(file, out.toString());
    }
}
