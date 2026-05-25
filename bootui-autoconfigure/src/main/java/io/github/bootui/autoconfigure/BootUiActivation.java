package io.github.bootui.autoconfigure;

import java.util.List;

/**
 * Resolved BootUI activation state at startup time.
 */
public record BootUiActivation(boolean enabled, String reason, List<String> warnings) {
}
