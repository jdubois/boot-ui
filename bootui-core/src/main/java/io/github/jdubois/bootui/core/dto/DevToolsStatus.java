package io.github.jdubois.bootui.core.dto;

/**
 * DevTools-backed reload and restart status.
 */
public record DevToolsStatus(
        boolean restartAvailable,
        String restartUnavailableReason,
        boolean restartPending,
        boolean liveReloadAvailable,
        Integer liveReloadPort,
        int liveReloadConnections,
        String liveReloadUnavailableReason) {}
