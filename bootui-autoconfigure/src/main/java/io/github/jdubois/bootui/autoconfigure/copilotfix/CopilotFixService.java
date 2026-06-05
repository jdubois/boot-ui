package io.github.jdubois.bootui.autoconfigure.copilotfix;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.GitHubTokenProvider;
import io.github.jdubois.bootui.core.dto.CopilotFixAvailabilityDto;
import io.github.jdubois.bootui.core.dto.CopilotFixDescriptorDto;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates "Fix it with Copilot" runs: resolves auth, isolates the edits on a dedicated branch,
 * invokes the agent, captures the resulting diff and streams progress.
 *
 * <p>Strictly a local developer tool. The GitHub token is resolved only to hand to the agent and is
 * never stored on a run, returned to the browser, or logged. Runs are serialized on a single
 * background thread so concurrency stays bounded.
 */
public class CopilotFixService {

    private static final Logger log = LoggerFactory.getLogger(CopilotFixService.class);

    /** Number of recent runs retained for polling/streaming after they finish. */
    private static final int MAX_RETAINED_RUNS = 20;

    private final BootUiProperties properties;
    private final GitHubTokenProvider tokenProvider;
    private final CopilotFixAgent agent;
    private final Function<Path, GitWorkspace> workspaceFactory;
    private final Path repoRoot;
    private final Executor executor;
    private final boolean ownsExecutor;

    private final Map<String, CopilotFixRun> runs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> runOrder = new ConcurrentLinkedDeque<>();

    public CopilotFixService(BootUiProperties properties) {
        this(
                properties,
                GitHubTokenProvider.defaultProvider(),
                new SdkCopilotFixAgent(),
                ProcessGitWorkspace::new,
                Path.of(System.getProperty("user.dir", ".")),
                newWorkerExecutor(),
                true);
    }

    CopilotFixService(
            BootUiProperties properties,
            GitHubTokenProvider tokenProvider,
            CopilotFixAgent agent,
            Function<Path, GitWorkspace> workspaceFactory,
            Path repoRoot,
            Executor executor) {
        this(properties, tokenProvider, agent, workspaceFactory, repoRoot, executor, false);
    }

    private CopilotFixService(
            BootUiProperties properties,
            GitHubTokenProvider tokenProvider,
            CopilotFixAgent agent,
            Function<Path, GitWorkspace> workspaceFactory,
            Path repoRoot,
            Executor executor,
            boolean ownsExecutor) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.agent = agent;
        this.workspaceFactory = workspaceFactory;
        this.repoRoot = repoRoot;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    private static ExecutorService newWorkerExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "bootui-copilot-fix");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    /** Whether the capability is enabled via configuration (independent of SDK/token presence). */
    public boolean enabled() {
        return properties.getCopilotFix().getEnabled() != BootUiProperties.Mode.OFF;
    }

    public CopilotFixAvailabilityDto availability() {
        BootUiProperties.CopilotFix settings = properties.getCopilotFix();
        boolean enabled = enabled();
        boolean sdkPresent = CopilotFixDetector.isSdkPresent();
        GitHubTokenProvider.Token token = resolveToken();
        boolean tokenPresent = token != null;
        String tokenSource = token == null ? null : token.source();
        String model = blankToNull(settings.getModel());
        boolean available = enabled && sdkPresent && tokenPresent;
        String reason = null;
        if (!enabled) {
            reason = "Disabled. Set bootui.copilot-fix.enabled=ON to allow automated fixes.";
        } else if (!sdkPresent) {
            reason = "The GitHub Copilot SDK for Java is not on the application classpath.";
        } else if (!tokenPresent) {
            reason = "No GitHub token found. Set GITHUB_TOKEN/GH_TOKEN or run 'gh auth login'.";
        }
        return new CopilotFixAvailabilityDto(
                available, reason, enabled, sdkPresent, tokenPresent, tokenSource, model);
    }

    /**
     * Starts a run for the given finding on the background worker and returns its initial state.
     *
     * @throws IllegalStateException when the capability is disabled
     * @throws IllegalArgumentException when the descriptor is missing a finding id
     */
    public CopilotFixRun start(CopilotFixDescriptorDto descriptor) {
        if (!enabled()) {
            throw new IllegalStateException("The Fix it with Copilot capability is disabled");
        }
        if (descriptor == null || descriptor.findingId() == null || descriptor.findingId().isBlank()) {
            throw new IllegalArgumentException("A finding id is required");
        }
        BootUiProperties.CopilotFix settings = properties.getCopilotFix();
        String runId = Long.toHexString(System.nanoTime());
        CopilotFixRun run =
                new CopilotFixRun(runId, descriptor.findingId(), System.currentTimeMillis(), settings.getMaxEventsPerRun());
        register(run);
        run.setStatus("RUNNING", "Starting");
        executor.execute(() -> execute(run, descriptor));
        return run;
    }

    @Nullable
    public CopilotFixRun getRun(String id) {
        return runs.get(id);
    }

    // Visible for tests: runs the full pipeline synchronously on the calling thread.
    void execute(CopilotFixRun run, CopilotFixDescriptorDto descriptor) {
        BootUiProperties.CopilotFix settings = properties.getCopilotFix();
        try {
            run.addEvent("status", "Preparing fix for " + descriptor.findingId() + ".");

            GitHubTokenProvider.Token token = resolveToken();
            if (token == null) {
                run.addEvent("error", "No GitHub token is available; cannot authenticate the agent.");
                finish(run, "FAILED", "No GitHub token available");
                return;
            }

            GitWorkspace workspace = workspaceFactory.apply(repoRoot);
            if (!workspace.available()) {
                String reason = workspace.unavailableReason();
                run.addEvent("error", reason == null ? "Git workspace is unavailable." : reason);
                finish(run, "FAILED", reason == null ? "Git workspace unavailable" : reason);
                return;
            }

            String branch = settings.getBranchPrefix() + sanitizeBranchSegment(descriptor.findingId()) + "-" + run.id();
            run.setBranch(branch);

            GitWorkspace.Isolated isolated;
            try {
                isolated = workspace.createIsolated(branch);
            } catch (GitWorkspace.GitWorkspaceException ex) {
                run.addEvent("error", "Could not create the isolated branch: " + ex.getMessage());
                finish(run, "FAILED", "Could not create isolated branch");
                return;
            }
            run.addEvent("status", "Created isolated branch " + branch + ".");

            boolean sdkPresent = CopilotFixDetector.isSdkPresent();
            boolean agentFailed = false;
            CopilotFixAgent.Context context = new CopilotFixAgent.Context(
                    isolated.directory(),
                    descriptor,
                    CopilotFixPromptBuilder.systemPrompt(),
                    CopilotFixPromptBuilder.userPrompt(descriptor),
                    blankToNull(settings.getModel()),
                    token.value());
            try {
                agent.run(context, (type, message) -> run.addEvent(type, message));
            } catch (RuntimeException ex) {
                agentFailed = true;
                run.addEvent("error", "The agent failed: " + safeMessage(ex));
            }

            GitWorkspace.Diff diff = workspace.capture(isolated);
            boolean hasChanges = diff.filesChanged() > 0;
            if (hasChanges) {
                run.setDiff(diff.unified(), diff.filesChanged());
                run.addEvent("diff", diff.filesChanged() + " file(s) changed on branch " + branch + ".");
            }

            workspace.cleanup(isolated, !hasChanges);

            if (agentFailed) {
                finish(run, "FAILED", "The agent did not complete successfully");
            } else if (hasChanges) {
                finish(
                        run,
                        "SUCCEEDED",
                        "Proposed " + diff.filesChanged() + " file change(s) on branch " + branch
                                + ". Review the diff before pushing.");
            } else if (!sdkPresent) {
                finish(run, "SDK_UNAVAILABLE", "The Copilot SDK is not on the classpath; no changes were made");
            } else {
                finish(run, "NO_CHANGES", "The agent did not propose any changes");
            }
        } catch (RuntimeException ex) {
            log.warn("BootUI Copilot fix run {} failed unexpectedly", run.id(), ex);
            run.addEvent("error", "Unexpected error: " + safeMessage(ex));
            finish(run, "FAILED", "Unexpected error");
        }
    }

    private void finish(CopilotFixRun run, String status, String message) {
        run.finish(status, message);
        run.addEvent("done", message);
    }

    private GitHubTokenProvider.@Nullable Token resolveToken() {
        try {
            return tokenProvider.token(Duration.ofSeconds(5));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void register(CopilotFixRun run) {
        runs.put(run.id(), run);
        runOrder.addLast(run.id());
        while (runOrder.size() > MAX_RETAINED_RUNS) {
            String oldest = runOrder.pollFirst();
            if (oldest != null) {
                runs.remove(oldest);
            }
        }
    }

    static String sanitizeBranchSegment(String value) {
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        cleaned = cleaned.replaceAll("(^-+)|(-+$)", "");
        if (cleaned.isBlank()) {
            cleaned = "finding";
        }
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    public void stop() {
        if (ownsExecutor && executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
