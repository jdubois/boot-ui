package io.github.jdubois.bootui.engine.constellation;

import java.util.List;

/**
 * Framework-neutral snapshot of one peer BootUI instance's identity, as read from its own
 * {@code GET /bootui/api/overview} and {@code GET /bootui/api/panels} endpoints.
 *
 * <p>Adapters are expected to parse the peer's JSON tolerantly (missing/renamed fields degrade to
 * {@code null}/empty rather than throwing) so an older or newer BootUI version on the other end never
 * breaks the whole Constellation view - see {@link PeerClient}.</p>
 */
public record PeerSnapshot(
        String url,
        boolean reachable,
        String applicationName,
        String platform,
        String frameworkVersion,
        String javaVersion,
        List<String> activeProfiles,
        String errorMessage) {

    public static PeerSnapshot unreachable(String url, String errorMessage) {
        return new PeerSnapshot(url, false, null, null, null, null, List.of(), errorMessage);
    }
}
