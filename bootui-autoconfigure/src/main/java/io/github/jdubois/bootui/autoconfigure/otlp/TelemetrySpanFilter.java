package io.github.jdubois.bootui.autoconfigure.otlp;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;

public final class TelemetrySpanFilter {

    private TelemetrySpanFilter() {}

    public static boolean isSelfSpan(NormalizedSpan span, String apiPath) {
        return BootUiSelfDataFilter.forPaths("/bootui", apiPath).isBootUiSpan(span);
    }
}
