/**
 * Framework-neutral GitHub dashboard orchestration: local repository detection from a {@code .git}
 * layout, the cached dashboard/refresh state machine, and the unavailable/ready/disabled report
 * assembly over BootUI core DTOs.
 *
 * <p>Plain Java (BootUI core DTOs + JDK only, no JSON or framework types). Adapters implement the
 * {@link io.github.jdubois.bootui.engine.github.GitHubClient} and
 * {@link io.github.jdubois.bootui.engine.github.GitHubTokenProvider} SPIs with their own HTTP and JSON
 * stack and wire {@link io.github.jdubois.bootui.engine.github.GitHubDashboardService} through its
 * {@code using(...)} factory. {@link io.github.jdubois.bootui.engine.github.GitHubRepositoryDetector}
 * and {@link io.github.jdubois.bootui.engine.github.DefaultGitHubTokenProvider} are reusable across
 * frameworks (filesystem and {@code gh} CLI only).
 */
package io.github.jdubois.bootui.engine.github;
