package io.github.jdubois.bootui.core.dto;

import java.util.Map;

/**
 * A single kernel-level event captured by an Inspektor Gadget gadget, normalized into a stable
 * shape for the Kernel Insights panel.
 *
 * <p>The {@code fields} map carries the flattened, stringified gadget-specific attributes so the UI
 * can render details without binding to a per-gadget schema. {@code comm}, {@code pid}, and
 * {@code container} are best-effort extractions of the originating process and container; any may be
 * {@code null} when the source gadget does not provide them.
 */
public record KernelEventDto(
        String timestamp, String comm, Integer pid, String container, String summary, Map<String, String> fields) {

    public KernelEventDto {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
