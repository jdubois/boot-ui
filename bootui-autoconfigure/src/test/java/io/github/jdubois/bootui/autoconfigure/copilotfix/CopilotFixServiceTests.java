package io.github.jdubois.bootui.autoconfigure.copilotfix;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.GitHubTokenProvider;
import io.github.jdubois.bootui.core.dto.CopilotFixAvailabilityDto;
import io.github.jdubois.bootui.core.dto.CopilotFixDescriptorDto;
import io.github.jdubois.bootui.core.dto.CopilotFixRunDto;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CopilotFixServiceTests {

    private static final String SECRET = "ghp_topsecrettoken_should_never_leak";

    private static CopilotFixDescriptorDto descriptor() {
        return new CopilotFixDescriptorDto(
                "GHSA-aaaa", "vulnerabilities", "Vulnerable lib", "Upgrade", "HIGH", List.of("org.example:lib:1.0.0"));
    }

    private static BootUiProperties propertiesEnabled() {
        BootUiProperties properties = new BootUiProperties();
        properties.getCopilotFix().setEnabled(BootUiProperties.Mode.ON);
        return properties;
    }

    @Test
    void startProducesSucceededRunWhenAgentMakesChanges() {
        BootUiProperties properties = propertiesEnabled();
        FakeGitWorkspace workspace = new FakeGitWorkspace(true, new GitWorkspace.Diff("diff", 2));
        CopilotFixAgent agent = (context, listener) -> listener.onEvent("progress", "working");
        CopilotFixService service = new CopilotFixService(
                properties, tokenProvider(SECRET), agent, root -> workspace, Path.of("."), Runnable::run);

        CopilotFixRun run = service.start(descriptor());
        CopilotFixRunDto snapshot = run.snapshot();

        assertThat(snapshot.status()).isEqualTo("SUCCEEDED");
        assertThat(snapshot.filesChanged()).isEqualTo(2);
        assertThat(snapshot.branch()).startsWith("bootui/fix-ghsa-aaaa-");
        assertThat(workspace.deleteBranchOnCleanup).isFalse();
        service.stop();
    }

    @Test
    void startProducesSdkUnavailableWhenNoChangesAndSdkAbsent() {
        BootUiProperties properties = propertiesEnabled();
        FakeGitWorkspace workspace = new FakeGitWorkspace(true, new GitWorkspace.Diff("", 0));
        CopilotFixAgent agent = (context, listener) -> {};
        CopilotFixService service = new CopilotFixService(
                properties, tokenProvider(SECRET), agent, root -> workspace, Path.of("."), Runnable::run);

        CopilotFixRun run = service.start(descriptor());

        assertThat(run.snapshot().status()).isEqualTo("SDK_UNAVAILABLE");
        assertThat(workspace.deleteBranchOnCleanup).isTrue();
        service.stop();
    }

    @Test
    void startFailsWhenNoTokenAvailable() {
        BootUiProperties properties = propertiesEnabled();
        FakeGitWorkspace workspace = new FakeGitWorkspace(true, new GitWorkspace.Diff("", 0));
        CopilotFixService service = new CopilotFixService(
                properties, tokenProvider(null), (context, listener) -> {}, root -> workspace, Path.of("."), Runnable::run);

        CopilotFixRun run = service.start(descriptor());

        assertThat(run.snapshot().status()).isEqualTo("FAILED");
        assertThat(workspace.created).isFalse();
        service.stop();
    }

    @Test
    void startFailsWhenWorkspaceUnavailable() {
        BootUiProperties properties = propertiesEnabled();
        FakeGitWorkspace workspace = new FakeGitWorkspace(false, new GitWorkspace.Diff("", 0));
        CopilotFixService service = new CopilotFixService(
                properties, tokenProvider(SECRET), (context, listener) -> {}, root -> workspace, Path.of("."), Runnable::run);

        CopilotFixRun run = service.start(descriptor());

        assertThat(run.snapshot().status()).isEqualTo("FAILED");
        service.stop();
    }

    @Test
    void agentFailureProducesFailedRun() {
        BootUiProperties properties = propertiesEnabled();
        FakeGitWorkspace workspace = new FakeGitWorkspace(true, new GitWorkspace.Diff("", 0));
        CopilotFixAgent agent = (context, listener) -> {
            throw new RuntimeException("boom");
        };
        CopilotFixService service = new CopilotFixService(
                properties, tokenProvider(SECRET), agent, root -> workspace, Path.of("."), Runnable::run);

        CopilotFixRun run = service.start(descriptor());

        assertThat(run.snapshot().status()).isEqualTo("FAILED");
        service.stop();
    }

    @Test
    void tokenIsHandedToTheAgentButNeverLeakedIntoTheSnapshot() {
        BootUiProperties properties = propertiesEnabled();
        FakeGitWorkspace workspace = new FakeGitWorkspace(true, new GitWorkspace.Diff("d", 1));
        AtomicBoolean tokenSeen = new AtomicBoolean();
        CopilotFixAgent agent = (context, listener) -> {
            if (SECRET.equals(context.token())) {
                tokenSeen.set(true);
            }
        };
        CopilotFixService service = new CopilotFixService(
                properties, tokenProvider(SECRET), agent, root -> workspace, Path.of("."), Runnable::run);

        CopilotFixRun run = service.start(descriptor());

        assertThat(tokenSeen).isTrue();
        assertThat(run.snapshot().toString()).doesNotContain(SECRET);
        assertThat(run.snapshot().events().toString()).doesNotContain(SECRET);
        service.stop();
    }

    @Test
    void startRejectsBlankFindingId() {
        CopilotFixService service = new CopilotFixService(
                propertiesEnabled(),
                tokenProvider(SECRET),
                (context, listener) -> {},
                root -> new FakeGitWorkspace(true, new GitWorkspace.Diff("", 0)),
                Path.of("."),
                Runnable::run);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.start(new CopilotFixDescriptorDto("  ", "s", "t", "u", "HIGH", List.of())));
        service.stop();
    }

    @Test
    void startRejectedWhenDisabled() {
        BootUiProperties properties = new BootUiProperties(); // default OFF
        CopilotFixService service = new CopilotFixService(
                properties,
                tokenProvider(SECRET),
                (context, listener) -> {},
                root -> new FakeGitWorkspace(true, new GitWorkspace.Diff("", 0)),
                Path.of("."),
                Runnable::run);

        assertThat(service.enabled()).isFalse();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> service.start(descriptor()));
        service.stop();
    }

    @Test
    void availabilityNeverExposesTheToken() {
        BootUiProperties properties = propertiesEnabled();
        CopilotFixService service = new CopilotFixService(
                properties,
                tokenProvider(SECRET),
                (context, listener) -> {},
                root -> new FakeGitWorkspace(true, new GitWorkspace.Diff("", 0)),
                Path.of("."),
                Runnable::run);

        CopilotFixAvailabilityDto availability = service.availability();

        assertThat(availability.enabled()).isTrue();
        assertThat(availability.tokenPresent()).isTrue();
        assertThat(availability.sdkPresent()).isFalse();
        assertThat(availability.available()).isFalse();
        assertThat(availability.toString()).doesNotContain(SECRET);
        service.stop();
    }

    @Test
    void sanitizeBranchSegmentNormalizesIds() {
        assertThat(CopilotFixService.sanitizeBranchSegment("GHSA/With Spaces!")).isEqualTo("ghsa-with-spaces");
        assertThat(CopilotFixService.sanitizeBranchSegment("   ")).isEqualTo("finding");
    }

    private static GitHubTokenProvider tokenProvider(String value) {
        return timeout -> value == null ? null : new GitHubTokenProvider.Token(value, "test");
    }

    private static final class FakeGitWorkspace implements GitWorkspace {
        private final boolean available;
        private final Diff diff;
        boolean created;
        boolean deleteBranchOnCleanup;

        FakeGitWorkspace(boolean available, Diff diff) {
            this.available = available;
            this.diff = diff;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String unavailableReason() {
            return available ? null : "Not a git repository";
        }

        @Override
        public Isolated createIsolated(String branch) {
            created = true;
            return new Isolated(Path.of(System.getProperty("java.io.tmpdir")), branch);
        }

        @Override
        public Diff capture(Isolated isolated) {
            return diff;
        }

        @Override
        public void cleanup(Isolated isolated, boolean deleteBranch) {
            this.deleteBranchOnCleanup = deleteBranch;
        }
    }
}
