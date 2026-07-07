package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One node in the Constellation view's local peer topology: the identity BootUI could read from a
 * configured peer's own {@code GET /bootui/api/overview} and {@code GET /bootui/api/panels} endpoints.
 *
 * <p>Peer data is read on a best-effort basis: an unreachable peer, a non-BootUI service, or an older
 * BootUI version missing a field still renders a node (with {@code reachable=false} or blank fields)
 * rather than failing the whole view. See {@code ConstellationService} in {@code bootui-engine}.</p>
 */
public record PeerNodeDto(
        String url,
        boolean reachable,
        String applicationName,
        String platform,
        String frameworkVersion,
        String javaVersion,
        List<String> activeProfiles,
        String errorMessage) {}
