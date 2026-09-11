package io.github.jdubois.bootui.core.dto;

/** Canonical Live Activity page, unchanged, with optional local bean-capture setup information. */
public record ExplorerReport(boolean available, LiveActivityReport activity, ExplorerSetupDto setup) {}
