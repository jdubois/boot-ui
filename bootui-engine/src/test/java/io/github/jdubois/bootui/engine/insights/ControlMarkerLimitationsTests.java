package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LifecyclePayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M4-7: a marker in an observation's window is named, and one that targeted what it counts is its limitation. */
class ControlMarkerLimitationsTests {

    private static final long NOON = 1_790_000_000_000L;

    @Test
    void theWindowNamesItsMarkersAndAFindingNamesTheActionThatTargetedWhatItCounts() {
        List<RuntimeEvent> markers = List.of(
                marker(LifecyclePayload.ACTION, "loggers: POST /loggers/org.hibernate.SQL"),
                marker(LifecyclePayload.ACTION, "cache: POST /cache/clear"),
                marker(LifecyclePayload.AVAILABILITY, "Readiness REFUSING_TRAFFIC"),
                marker(LifecyclePayload.SHUTDOWN, null));
        Finding warnings = new Finding(
                "k",
                "GET /api/orders",
                true,
                "`GET /api/orders` logged 3 warnings from `org.hibernate.SQL`.",
                3,
                3,
                List.of(),
                List.of(),
                List.of("Logger"),
                List.of(List.of("org.hibernate.SQL")),
                List.of());

        assertThat(RuntimeInsightsService.markersDuring(markers))
                .startsWith("During this window: BootUI action (loggers: POST /loggers/org.hibernate.SQL) at 2026-")
                .contains("; Availability changed (Readiness REFUSING_TRAFFIC) at ")
                .endsWith("; and 1 more. What ran before and after such a change may differ.");
        assertThat(RuntimeInsightsService.markersDuring(List.of())).isNull();
        assertThat(RuntimeInsightsService.touchedBy(markers, warnings))
                .singleElement()
                .asString()
                .startsWith("BootUI's action loggers: POST /loggers/org.hibernate.SQL at ")
                .endsWith(" targeted `org.hibernate.SQL` during this window.");
    }

    private static RuntimeEvent marker(String kind, String target) {
        return RuntimeEvent.of(
                JournalSource.LIFECYCLE,
                NOON,
                0,
                CorrelationContext.NONE,
                null,
                null,
                false,
                new LifecyclePayload(kind, target, null));
    }
}
