package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationDetailsDto;
import io.github.jdubois.bootui.engine.support.PagedList;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Latest completed report and its immutable detail index, published as one snapshot.
 * Scanners call {@link #publish} inside their existing single-flight admission.
 */
public final class AdvisorScanState<R> {

    private static final int DEFAULT_RETENTION_LIMIT = 10_000;
    private static final int DEFAULT_PAGE_LIMIT = 100;
    private static final int MAX_PAGE_LIMIT = 1_000;

    private final BiFunction<R, AdvisorViolationDetailsDto, R> withMetadata;
    private volatile IntSupplier retentionLimit = () -> DEFAULT_RETENTION_LIMIT;
    private volatile Completed<R> completed;
    private R initialReport;

    public AdvisorScanState(BiFunction<R, AdvisorViolationDetailsDto, R> withMetadata) {
        this.withMetadata = Objects.requireNonNull(withMetadata, "Advisor metadata projection is required.");
    }

    public AdvisorViolationCollector collector() {
        return new AdvisorViolationCollector(retentionLimit.getAsInt());
    }

    public void setRetentionLimit(IntSupplier retentionLimit) {
        Objects.requireNonNull(retentionLimit, "Advisor violation retention policy is required.");
        if (retentionLimit.getAsInt() <= 0) {
            throw new IllegalArgumentException("Advisor violation retention limit must be positive.");
        }
        this.retentionLimit = retentionLimit;
    }

    public R publish(R report, AdvisorViolationCollector collector) {
        Objects.requireNonNull(report, "Advisor report is required.");
        Objects.requireNonNull(collector, "Advisor violation collector is required.");
        AdvisorViolationCollector.Snapshot index = collector.snapshot();
        AdvisorViolationDetailsDto metadata = new AdvisorViolationDetailsDto(
                UUID.randomUUID().toString(),
                index.total(),
                index.retained(),
                index.retentionLimit(),
                index.retained() < index.total(),
                index.locationNotes());
        R published = Objects.requireNonNull(
                withMetadata.apply(report, metadata), "Advisor metadata projection must return a report.");
        completed = new Completed<>(published, metadata, index);
        return published;
    }

    public R currentReport(Supplier<R> initial) {
        Completed<R> snapshot = completed;
        if (snapshot != null) {
            return snapshot.report();
        }
        synchronized (this) {
            snapshot = completed;
            if (snapshot != null) {
                return snapshot.report();
            }
            if (initialReport == null) {
                initialReport = Objects.requireNonNull(initial.get(), "Initial advisor report is required.");
            }
            snapshot = completed;
            return snapshot == null ? initialReport : snapshot.report();
        }
    }

    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new AdvisorViolationException(400, "Advisor rule ID must not be blank.");
        }
        if (scanId == null || scanId.isBlank()) {
            throw new AdvisorViolationException(400, "Advisor scan ID must not be blank.");
        }
        if (offset != null && offset < 0) {
            throw new AdvisorViolationException(400, "Advisor violation offset must not be negative.");
        }
        if (limit != null && limit <= 0) {
            throw new AdvisorViolationException(400, "Advisor violation limit must be positive.");
        }

        Completed<R> snapshot = completed;
        if (snapshot == null) {
            throw new AdvisorViolationException(
                    409, "No completed advisor scan is available. Reread the cached report before requesting details.");
        }
        if (!snapshot.metadata().scanId().equals(scanId)) {
            throw new AdvisorViolationException(
                    409, "Advisor scan has been replaced. Reread the cached report before requesting details.");
        }
        AdvisorViolationCollector.Rule rule = snapshot.index().rules().get(ruleId);
        if (rule == null) {
            throw new AdvisorViolationException(404, "Advisor rule has no findings in the current scan.");
        }

        int pageLimit = limit == null ? DEFAULT_PAGE_LIMIT : Math.min(limit, MAX_PAGE_LIMIT);
        PagedList.Result<AdvisorViolation> page =
                PagedList.from(rule.violations(), offset == null ? 0 : offset, pageLimit);
        // Text and location are cut from the same retained records, so the two lists stay aligned on every page.
        return new AdvisorRuleViolationsDto(
                snapshot.metadata().scanId(),
                ruleId,
                rule.violationCount(),
                rule.violations().size(),
                rule.violations().size() < rule.violationCount(),
                AdvisorViolation.texts(page.items()),
                page.page(),
                AdvisorViolation.locations(page.items()));
    }

    private record Completed<R>(
            R report, AdvisorViolationDetailsDto metadata, AdvisorViolationCollector.Snapshot index) {}
}
