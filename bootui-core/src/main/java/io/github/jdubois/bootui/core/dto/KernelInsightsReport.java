package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the Kernel Insights panel, which surfaces kernel-level observability captured
 * with the local <a href="https://inspektor-gadget.io">Inspektor Gadget</a> {@code ig} binary.
 *
 * <p>{@code status} is one of {@code NOT_SCANNED}, {@code SCANNED}, {@code DISABLED},
 * {@code UNAVAILABLE}, or {@code ERROR}. {@code available} reflects whether a capture can run in the
 * current environment (Linux host with the {@code ig} binary present and the feature enabled).
 * {@code currentPid} is the host application's process id, shown so a capture can be related back to
 * the running app.
 */
public record KernelInsightsReport(
        boolean available,
        String status,
        String message,
        String os,
        String igPath,
        String igVersion,
        Long currentPid,
        Long scannedAt,
        int captureSeconds,
        List<KernelGadgetResult> gadgets) {

    public KernelInsightsReport {
        gadgets = gadgets == null ? List.of() : List.copyOf(gadgets);
    }
}
