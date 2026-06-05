package io.github.jdubois.bootui.core.dto;

/**
 * Browser-safe availability status for the "Fix it with Copilot" capability.
 *
 * <p>Never exposes the GitHub token itself - only the non-secret {@code tokenSource} label
 * (for example {@code GITHUB_TOKEN} or {@code gh auth token}) so the UI can explain how the
 * agent would authenticate.
 *
 * @param available whether the capability is ready to run a fix
 * @param unavailableReason human-readable reason when {@code available} is {@code false}
 * @param enabled whether the capability is enabled via configuration
 * @param sdkPresent whether the GitHub Copilot SDK is on the application classpath
 * @param tokenPresent whether a GitHub token could be resolved locally
 * @param tokenSource non-secret label describing where the token came from, or {@code null}
 * @param model the model the agent would use, or {@code null} for the SDK default
 */
public record CopilotFixAvailabilityDto(
        boolean available,
        String unavailableReason,
        boolean enabled,
        boolean sdkPresent,
        boolean tokenPresent,
        String tokenSource,
        String model) {}
