package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationDetailsDto;
import io.github.jdubois.bootui.engine.support.PagedList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
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

    /**
     * A rule of this advisor's catalogue with no retained finding in the current scan: it passed, was skipped, or could
     * not be evaluated, which the cached report's results and analysisErrors tell apart.
     */
    public static final String NO_FINDINGS_MESSAGE = "Advisor rule has no findings in the current scan.";

    /** A rule id outside this advisor's catalogue: a typo, a retired rule, or another advisor's rule. */
    public static final String UNKNOWN_RULE_MESSAGE =
            "Unknown advisor rule: this advisor has no rule with this id. Use a rule id from the cached report.";

    private final BiFunction<R, AdvisorViolationDetailsDto, R> withMetadata;
    private final Supplier<? extends Collection<String>> ruleCatalog;
    private volatile IntSupplier retentionLimit = () -> DEFAULT_RETENTION_LIMIT;
    private volatile Completed<R> completed;
    private R initialReport;

    /**
     * A state that cannot tell an unknown rule id from a rule without findings, so it answers {@link
     * #NO_FINDINGS_MESSAGE} for both.
     */
    public AdvisorScanState(BiFunction<R, AdvisorViolationDetailsDto, R> withMetadata) {
        this(withMetadata, () -> null);
    }

    /**
     * @param ruleCatalog the ids of every rule the advisor runs, read when a scan is published, so that a detail read
     *     answers an id outside them with {@link #UNKNOWN_RULE_MESSAGE} and any of them without a retained finding with
     *     {@link #NO_FINDINGS_MESSAGE}. A report's results cannot serve: they list only violating rules. {@code null}
     *     from it means unknown
     */
    public AdvisorScanState(
            BiFunction<R, AdvisorViolationDetailsDto, R> withMetadata,
            Supplier<? extends Collection<String>> ruleCatalog) {
        this.withMetadata = Objects.requireNonNull(withMetadata, "Advisor metadata projection is required.");
        this.ruleCatalog = Objects.requireNonNull(ruleCatalog, "Advisor rule catalogue is required.");
    }

    /** The ids of {@code rules}, read with {@code id}, for {@link #AdvisorScanState(BiFunction, Supplier)}. */
    public static <T> List<String> ruleIds(List<T> rules, Function<T, String> id) {
        return rules == null
                ? null
                : rules.stream().map(id).filter(Objects::nonNull).toList();
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
        Collection<String> catalog = ruleCatalog.get();
        completed = new Completed<>(published, metadata, index, catalog == null ? null : Set.copyOf(catalog));
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
            Set<String> catalog = snapshot.ruleCatalog();
            throw new AdvisorViolationException(
                    404, catalog == null || catalog.contains(ruleId) ? NO_FINDINGS_MESSAGE : UNKNOWN_RULE_MESSAGE);
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

    /** @param ruleCatalog the advisor's rules when the report was published, or {@code null} when unknown */
    private record Completed<R>(
            R report,
            AdvisorViolationDetailsDto metadata,
            AdvisorViolationCollector.Snapshot index,
            Set<String> ruleCatalog) {}
}
