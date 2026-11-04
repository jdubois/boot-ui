package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the update notification, whose whole point is to be unobtrusive: the tests are mostly about the
 * cases where it must say and do nothing.
 */
class UpdateCheckTests {

    @Test
    void ciCommandsNeverEnableTheUpdateCheckEvenOnATerminal() {
        assertThat(BootUiCli.updateCheckTerminal(new String[] {"ci", "attach"}, true))
                .isFalse();
        assertThat(BootUiCli.updateCheckTerminal(new String[] {"--verbose", "ci", "assess"}, true))
                .isFalse();
        assertThat(BootUiCli.updateCheckTerminal(new String[] {"overview"}, true))
                .isTrue();
    }

    private static final String METADATA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>com.julien-dubois.bootui</groupId>
              <artifactId>bootui-cli</artifactId>
              <versioning>
                <latest>1.17.0</latest>
                <release>1.16.0</release>
                <versions><version>1.15.0</version><version>1.16.0</version></versions>
              </versioning>
            </metadata>
            """;

    @TempDir
    Path stateDir;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private String metadata = METADATA;
    private int status = 200;

    @BeforeEach
    void startRepository() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = metadata.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopRepository() {
        server.stop(0);
    }

    @Test
    void refreshRecordsTheNewestReleaseAndTheTimeItAsked() {
        newCheck("1.15.0").refresh();

        assertThat(stateDir.resolve("latest-version")).content().isEqualToIgnoringNewLines("1.16.0");
        assertThat(stateDir.resolve("update-checked-at")).exists();
        assertThat(requests).hasValue(1);
    }

    @Test
    void aNewerReleaseIsReportedOnStandardError() {
        write("latest-version", "1.16.0");

        assertThat(report("1.15.0"))
                .contains("bootui: version 1.16.0 is available; you have 1.15.0.")
                .contains("Silence this with BOOTUI_NO_UPDATE_CHECK=1.");
    }

    @Test
    void theInstalledVersionBeingCurrentOrNewerIsNotWorthAWord() {
        write("latest-version", "1.16.0");

        assertThat(report("1.16.0")).isEmpty();
        assertThat(report("1.17.0")).isEmpty();
    }

    @Test
    void nothingIsReportedBeforeACheckHasEverCompleted() {
        assertThat(report("1.15.0")).isEmpty();
    }

    @Test
    void anUnreadableCachedAnswerIsIgnoredRatherThanPrinted() {
        write("latest-version", "not a version; rm -rf /");

        assertThat(report("1.15.0")).isEmpty();
    }

    @Test
    void aCheckMadeTodayIsNotMadeAgain() {
        write("update-checked-at", Instant.now().toString());

        UpdateCheck check = newCheck("1.15.0");
        check.startRefreshIfDue();
        check.awaitRefresh();

        assertThat(requests).hasValue(0);
    }

    @Test
    void aCheckOlderThanADayIsMadeAgain() throws IOException {
        write("update-checked-at", "stale");
        Files.setLastModifiedTime(
                stateDir.resolve("update-checked-at"),
                java.nio.file.attribute.FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        UpdateCheck check = newCheck("1.15.0");
        check.startRefreshIfDue();
        check.awaitRefresh();

        assertThat(requests).hasValue(1);
    }

    @Test
    void aRepositoryThatAnswersWithAnErrorLeavesTheCachedAnswerAlone() {
        write("latest-version", "1.16.0");
        status = 500;

        newCheck("1.15.0").refresh();

        assertThat(stateDir.resolve("latest-version")).content().isEqualToIgnoringNewLines("1.16.0");
        // The question was settled — the repository answered — so it is not asked again today.
        assertThat(stateDir.resolve("update-checked-at")).exists();
    }

    @Test
    void aRepositoryThatCannotBeReachedIsNotAFailure() {
        UpdateCheck check = new UpdateCheck(
                true, "1.15.0", stateDir, URI.create("http://127.0.0.1:1/maven-metadata.xml"), "update me");

        check.refresh();

        assertThat(stateDir.resolve("latest-version")).doesNotExist();
        // The attempt finished, it just found nothing, so it is not repeated on every command today.
        assertThat(stateDir.resolve("update-checked-at")).exists();
    }

    @Test
    void metadataWithoutAReleaseFallsBackToTheLatestVersion() {
        metadata = "<metadata><versioning><latest>2.0.0</latest></versioning></metadata>";

        newCheck("1.15.0").refresh();

        assertThat(stateDir.resolve("latest-version")).content().isEqualToIgnoringNewLines("2.0.0");
    }

    @Test
    void aPipedRunChecksNothingAndSaysNothing() {
        write("latest-version", "1.16.0");

        UpdateCheck check = UpdateCheck.create("1.15.0", environment(), false);
        check.startRefreshIfDue();
        check.awaitRefresh();

        assertThat(collect(check::report)).isEmpty();
        assertThat(requests).hasValue(0);
    }

    @Test
    void theCheckCanBeTurnedOff() {
        write("latest-version", "1.16.0");
        Map<String, String> environment = new java.util.HashMap<>(environment());
        environment.put(UpdateCheck.DISABLE_ENV, "1");

        UpdateCheck check = UpdateCheck.create("1.15.0", environment, true);
        check.startRefreshIfDue();
        check.awaitRefresh();

        assertThat(collect(check::report)).isEmpty();
        assertThat(requests).hasValue(0);
    }

    @Test
    void aJarBuiltOutsideAReleaseHasNothingToCompareAgainst() {
        write("latest-version", "1.16.0");

        UpdateCheck check = UpdateCheck.create("dev", environment(), true);
        check.startRefreshIfDue();
        check.awaitRefresh();

        assertThat(collect(check::report)).isEmpty();
        assertThat(requests).hasValue(0);
    }

    @Test
    void anInstallReadsTheRepositoryAndDirectoryItWasInstalledWith() {
        UpdateCheck check = UpdateCheck.create("1.15.0", environment(), true);

        check.refresh();

        assertThat(stateDir.resolve("latest-version")).content().isEqualToIgnoringNewLines("1.16.0");
        assertThat(requests).hasValue(1);
    }

    @Test
    void versionsAreComparedByNumberWithAQualifierRankedBelowTheReleaseItLeadsTo() {
        assertThat(UpdateCheck.isNewer("1.16.0", "1.15.0")).isTrue();
        assertThat(UpdateCheck.isNewer("1.16.0", "1.16.0")).isFalse();
        assertThat(UpdateCheck.isNewer("1.15.0", "1.16.0")).isFalse();
        assertThat(UpdateCheck.isNewer("2.0.0", "1.99.9")).isTrue();
        assertThat(UpdateCheck.isNewer("1.16", "1.15.3")).isTrue();
        assertThat(UpdateCheck.isNewer("1.16.0", "1.16")).isFalse();
        assertThat(UpdateCheck.isNewer("1.16.0", "1.16.0-rc1")).isTrue();
        assertThat(UpdateCheck.isNewer("1.16.0-rc1", "1.16.0")).isFalse();
        assertThat(UpdateCheck.isNewer("1.17.0-rc1", "1.16.0")).isTrue();
        assertThat(UpdateCheck.isNewer("nightly", "1.16.0")).isFalse();
        assertThat(UpdateCheck.isNewer(null, "1.16.0")).isFalse();
    }

    private UpdateCheck newCheck(String installedVersion) {
        return new UpdateCheck(true, installedVersion, stateDir, metadataUrl(), "update me");
    }

    private Map<String, String> environment() {
        return Map.of(
                UpdateCheck.STATE_DIR_ENV,
                stateDir.toString(),
                UpdateCheck.REPOSITORY_ENV,
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private URI metadataUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/com/julien-dubois/bootui/bootui-cli/maven-metadata.xml");
    }

    private String report(String installedVersion) {
        return collect(newCheck(installedVersion)::report);
    }

    private static String collect(java.util.function.Consumer<PrintWriter> reporter) {
        StringWriter captured = new StringWriter();
        PrintWriter writer = new PrintWriter(captured, true);
        reporter.accept(writer);
        writer.flush();
        return captured.toString();
    }

    private void write(String name, String content) {
        try {
            Files.writeString(stateDir.resolve(name), content + "\n");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
