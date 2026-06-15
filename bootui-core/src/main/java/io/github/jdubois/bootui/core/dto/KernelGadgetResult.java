package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of running a single Inspektor Gadget gadget during a Kernel Insights capture.
 *
 * <p>{@code status} is one of {@code OK}, {@code ERROR}, or {@code SKIPPED}. The {@code category}
 * groups gadgets in the UI ({@code NETWORK}, {@code DNS}, {@code PROCESS}, {@code SOCKET}).
 */
public record KernelGadgetResult(
        String gadget,
        String title,
        String category,
        String status,
        String message,
        int eventCount,
        List<KernelEventDto> events) {

    public KernelGadgetResult {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
