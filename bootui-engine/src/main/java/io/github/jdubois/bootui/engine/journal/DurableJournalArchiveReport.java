package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import java.util.List;
import java.util.Objects;

/** Runtime Insights projected independently from one or more durable journal runs. */
public record DurableJournalArchiveReport(List<Run> runs) {

    public DurableJournalArchiveReport {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    /** One run report together with the origin and completeness of its durable source. */
    public record Run(DurableJournalOrigin origin, Completeness completeness, RuntimeInsightsReportDto insights) {

        public Run {
            origin = Objects.requireNonNull(origin, "origin must not be null");
            completeness = Objects.requireNonNull(completeness, "completeness must not be null");
            insights = Objects.requireNonNull(insights, "insights must not be null");
        }
    }

    /** What durable evidence was available when one historical report was built. */
    public record Completeness(
            boolean complete,
            DurableJournalRunMetadata.Phase metadataPhase,
            long omittedEvents,
            List<String> limitations) {

        public Completeness {
            Objects.requireNonNull(metadataPhase, "metadataPhase must not be null");
            if (omittedEvents < 0) {
                throw new IllegalArgumentException("omittedEvents must not be negative");
            }
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }
}
