package io.github.jdubois.bootui.autoconfigure;

import java.util.List;

/**
 * Resolved BootUI activation state at startup time.
 *
 * @param forcedDespiteProfile the active {@code bootui.disabled-profiles} entry, such as {@code prod}, that
 *     {@code bootui.enabled=ON} overrode to turn BootUI on, or {@code null} when BootUI was not forced on despite one
 */
public record BootUiActivation(boolean enabled, String reason, List<String> warnings, String forcedDespiteProfile) {

    public BootUiActivation(boolean enabled, String reason, List<String> warnings) {
        this(enabled, reason, warnings, null);
    }
}
