package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The files sensor's path patterns and the files and environment sensors' caches (PLAN-v2 §5.16, M5-5d): pure
 * functions of the bridge, so the Windows and macOS rules are checked on every platform.
 */
class SideEffectPathsTests {

    private static final SideEffects.Places UNIX = new SideEffects.Places(
            null,
            "/srv/app",
            new String[] {"/srv/app"},
            new String[] {"/var/folders/x1/T", "/private/var/folders/x1/T"},
            new String[] {"/home/alice"},
            new String[] {"/opt/jdk"},
            new String[] {"/srv/app/target/classes"},
            false);

    private static final SideEffects.Places WINDOWS = new SideEffects.Places(
            null,
            "C:/work/app",
            new String[] {"C:/work/app"},
            new String[] {"C:/Users/Bob/AppData/Local/Temp"},
            new String[] {"C:/Users/Bob"},
            new String[] {"C:/Java/jdk"},
            new String[0],
            true);

    private static String pattern(String path, SideEffects.Places places) {
        return SideEffects.pattern(SideEffects.absolute(path, places), places);
    }

    @Test
    void theLongestOfTheTemporaryWorkingAndHomeDirectoriesIsReplaced() {
        assertThat(pattern("reports/report-2026-10-05.csv", UNIX)).isEqualTo("./reports/report-{n}-{n}-{n}.csv");
        assertThat(pattern("/srv/app/./out/../out/x.txt", UNIX)).isEqualTo("./out/x.txt");
        assertThat(pattern("/srv/app", UNIX)).isEqualTo(".");
        assertThat(pattern("/var/folders/x1/T/upload123.tmp", UNIX)).isEqualTo("$TMPDIR/upload{n}.tmp");
        assertThat(pattern("/private/var/folders/x1/T/a.txt", UNIX)).isEqualTo("$TMPDIR/a.txt");
        assertThat(pattern("/home/alice/.config/app.yml", UNIX)).isEqualTo("~/.config/app.yml");
        assertThat(pattern("/srv/application/x", UNIX))
                .as("a sibling sharing a prefix is not inside")
                .isEqualTo("/srv/application/x");
        assertThat(pattern("/../../etc/hosts", UNIX)).isEqualTo("/etc/hosts");
    }

    @Test
    void aTemporaryDirectoryInsideTheWorkingDirectoryWinsAsTheLongerPrefix() {
        SideEffects.Places nested = new SideEffects.Places(
                null,
                "/srv/app",
                new String[] {"/srv/app"},
                new String[] {"/srv/app/tmp"},
                new String[0],
                new String[0],
                new String[0],
                false);

        assertThat(pattern("/srv/app/tmp/a.txt", nested)).isEqualTo("$TMPDIR/a.txt");
        assertThat(pattern("/srv/app/data/a.txt", nested)).isEqualTo("./data/a.txt");
    }

    @Test
    void anotherUsersHomeNeverKeepsTheirName() {
        assertThat(pattern("/home/bob/secrets.txt", UNIX)).isEqualTo("/home/*/secrets.txt");
        assertThat(pattern("/Users/carol/Documents/x.pdf", UNIX)).isEqualTo("/Users/*/Documents/x.pdf");
        assertThat(pattern("D:\\Users\\dave\\notes.txt", WINDOWS)).isEqualTo("D:/Users/*/notes.txt");
        assertThat(pattern("\\\\srv\\Users\\erin\\notes.txt", WINDOWS)).isEqualTo("/srv/Users/*/notes.txt");
        assertThat(pattern("\\\\?\\D:\\Users\\frank\\notes.txt", WINDOWS)).isEqualTo("/?/D:/Users/*/notes.txt");
        assertThat(pattern("/var/home/grace/notes.txt", UNIX)).isEqualTo("/var/home/*/notes.txt");
        assertThat(pattern("/export/home/heidi/notes.txt", UNIX)).isEqualTo("/export/home/*/notes.txt");
        assertThat(pattern("/srv/site/home/index.html", UNIX))
                .as("a directory named home deeper in a path is not a home")
                .isEqualTo("/srv/site/home/index.html");
    }

    @Test
    void windowsPathsAreSlashSeparatedAndMatchedCaseInsensitively() {
        assertThat(pattern("c:\\users\\bob\\appdata\\local\\temp\\x1.tmp", WINDOWS))
                .isEqualTo("$TMPDIR/x{n}.tmp");
        assertThat(pattern("C:\\Users\\Bob\\report.csv", WINDOWS)).isEqualTo("~/report.csv");
        assertThat(pattern("logs\\app.log", WINDOWS)).isEqualTo("./logs/app.log");
        assertThat(SideEffects.bucketOfPath(SideEffects.absolute("C:\\JAVA\\JDK\\lib\\x", WINDOWS), WINDOWS))
                .isEqualTo(SideEffects.BUCKET_JAVA_HOME);
    }

    @Test
    void idsCollapseSoRandomNamesCannotFillTheTable() {
        assertThat(SideEffects.collapse("/u/550e8400-e29b-41d4-a716-446655440000.json"))
                .isEqualTo("/u/{uuid}.json");
        assertThat(SideEffects.collapse("/c/0123abcdef9.bin")).isEqualTo("/c/{hex}.bin");
        assertThat(SideEffects.collapse("/t/sk_live_abc123def456ghi789jkl.txt")).isEqualTo("/t/sk_live_{id}.txt");
        assertThat(SideEffects.collapse("/t/eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig/x"))
                .isEqualTo("/t/{token}/x");
        assertThat(SideEffects.collapse("/v2/release-17.txt")).isEqualTo("/v{n}/release-{n}.txt");
        assertThat(SideEffects.collapse("/plain/name.txt")).isEqualTo("/plain/name.txt");
        assertThat(SideEffects.collapse("/k/aB3dE5fG7hJ9kL1m.key")).isEqualTo("/k/{id}.key");
        assertThat(SideEffects.collapse("/k/report2026q.csv")).isEqualTo("/k/report{n}q.csv");
        assertThat(SideEffects.collapse("/k/QwErTyUiOpAsDfGhJkLzXcVbNm.txt")).isEqualTo("/k/{id}.txt");
        assertThat(SideEffects.collapse("/k/applicationconfigurationfile.txt"))
                .as("a long lower-case word is kept")
                .isEqualTo("/k/applicationconfigurationfile.txt");
    }

    @Test
    void bucketsAreDecidedByNameThenByPlace() {
        assertThat(SideEffects.bucketOfName("/a/B.CLASS")).isEqualTo(SideEffects.BUCKET_CLASS_FILES);
        assertThat(SideEffects.bucketOfName("lib/x-1.0.jar")).isEqualTo(SideEffects.BUCKET_ARCHIVES);
        assertThat(SideEffects.bucketOfName("app.war")).isEqualTo(SideEffects.BUCKET_ARCHIVES);
        assertThat(SideEffects.bucketOfName("report.csv")).isEqualTo(-1);
        assertThat(SideEffects.bucketOfPath("/opt/jdk/lib/security/cacerts", UNIX))
                .isEqualTo(SideEffects.BUCKET_JAVA_HOME);
        assertThat(SideEffects.bucketOfPath("/srv/app/target/classes/application.yml", UNIX))
                .isEqualTo(SideEffects.BUCKET_CLASS_PATH_DIRECTORIES);
        assertThat(SideEffects.bucketOfPath("/srv/app/target/report.csv", UNIX)).isEqualTo(-1);
    }

    @Test
    void placesKeepNoTrailingSeparatorAndNoRoot() {
        assertThat(SideEffects.Places.forms("/tmp/", false)).contains("/tmp").doesNotContain("/tmp/");
        assertThat(SideEffects.Places.forms("/", false)).isEmpty();
        assertThat(SideEffects.Places.forms(null, false)).isEmpty();
    }

    @Test
    void theSeenCacheIsBoundedAndClearedForAnotherOwner() {
        SideEffects.Seen seen = new SideEffects.Seen(true);
        seen.own(1L, 7L, 0L, true, 0L);
        for (int i = 0; i < 100; i++) {
            seen.put(SideEffects.Seen.key(0, 0, i), "name" + i, 0L, 0);
        }
        assertThat(seen.find(SideEffects.Seen.key(0, 0, 99), "name99")).isNotNegative();
        assertThat(seen.find(SideEffects.Seen.key(0, 0, 99), "other"))
                .as("names compare by equals")
                .isNegative();
        seen.own(1L, 8L, 0L, true, 0L);
        assertThat(seen.find(SideEffects.Seen.key(0, 0, 99), "name99")).isNegative();

        seen.own(1L, 0L, 0L, false, 1_000L);
        seen.put(SideEffects.Seen.key(0, 0, 1), "a", 0L, 0);
        seen.own(1L, 0L, 0L, false, 1_500L);
        assertThat(seen.find(SideEffects.Seen.key(0, 0, 1), "a"))
                .as("within a second")
                .isNotNegative();
        seen.own(1L, 0L, 0L, false, 1_000L + SideEffects.UNOWNED_SEEN_MILLIS);
        assertThat(seen.find(SideEffects.Seen.key(0, 0, 1), "a"))
                .as("an unowned thread's, expired")
                .isNegative();
    }

    @Test
    void theSightingsCacheIsPerGenerationAndStopsWhenFull() {
        SideEffects.Sightings sightings = new SideEffects.Sightings();
        long[] found = new long[2];
        assertThat(sightings.find(1L, 42L, found)).isEqualTo(SideEffects.Sightings.MISSING);
        sightings.put(1L, 42L, 99L, SideEffects.CONTEXT_CLASS_LOADING);
        assertThat(sightings.find(1L, 42L, found)).isEqualTo(SideEffects.Sightings.FOUND);
        assertThat(found).containsExactly(99L, SideEffects.CONTEXT_CLASS_LOADING);
        assertThat(sightings.find(2L, 42L, found)).as("another generation").isEqualTo(SideEffects.Sightings.MISSING);

        // Keys a multiple of SIZE apart share their home slot through the hash's low bits only by chance: fill one
        // key's probe window directly instead.
        SideEffects.Sightings crowded = new SideEffects.Sightings();
        int full = 0;
        for (long key = 1; key < 1_000_000 && full == 0; key++) {
            if (crowded.find(1L, key, found) == SideEffects.Sightings.FULL) {
                full++;
            } else {
                crowded.put(1L, key, key, 0);
            }
        }
        assertThat(full).as("a full probe window reports FULL, never loops").isEqualTo(1);
        sightings.clear();
        assertThat(sightings.find(1L, 42L, found)).isEqualTo(SideEffects.Sightings.MISSING);
    }
}
