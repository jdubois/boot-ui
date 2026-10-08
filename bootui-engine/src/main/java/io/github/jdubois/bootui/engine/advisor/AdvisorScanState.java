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

    /** A rule the current scan evaluated, with no recorded finding. */
    public static final String NO_FINDINGS_MESSAGE = "Advisor rule has no findings in the current scan.";

    /** A rule id the current scan did not evaluate at all: a typo, another advisor's rule, or a skipped rule. */
    public static final String UNKNOWN_RULE_MESSAGE = "Unknown advisor rule: the current scan evaluated no rule with"
            + " this id. Use a rule id from the results of the cached report.";

    private final BiFunction<R, AdvisorViolationDetailsDto, R> withMetadata;
    private final Function<R, ? extends Collection<String>> evaluatedRuleIds;
    private volatile IntSupplier retentionLimit = () -> DEFAULT_RETENTION_LIMIT;
    private volatile Completed<R> completed;
    private R initialReport;

    /**
     * A state that cannot tell an unknown rule id from a rule without findings, so it answers {@link
     * #NO_FINDINGS_MESSAGE} for both.
     */
    public AdvisorScanState(BiFunction<R, AdvisorViolationDetailsDto, R> withMetadata) {
        this(withMetadata, report -> null);
    }

    /**
     * @param evaluatedRuleIds the ids of the rules a published report evaluated (its results), so that a detail read
     *     answers an id the scan never evaluated with {@link #UNKNOWN_RULE_MESSAGE} rather than {@link
     *     #NO_FINDINGS_MESSAGE}; {@code null} from it means unknown
     */
    public AdvisorScanState(
            BiFunction<R, AdvisorViolationDetailsDto, R> withMetadata,
            Function<R, ? extends Collection<String>> evaluatedRuleIds) {
        this.withMetadata = Objects.requireNonNull(withMetadata, "Advisor metadata projection is required.");
        this.evaluatedRuleIds = Objects.requireNonNull(evaluatedRuleIds, "Advisor rule id projection is required.");
    }

    /** The ids of {@code results}, read with {@code id}, for {@link #AdvisorScanState(BiFunction, Function)}. */
    public static <T> List<String> ruleIds(List<T> results, Function<T, String> id) {
        return results == null
                ? null
                : results.stream().map(id).filter(Objects::nonNull).toList();
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
        Collection<String> evaluated = evaluatedRuleIds.apply(published);
        completed = new Completed<>(published, metadata, index, evaluated == null ? null : Set.copyOf(evaluated));
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
            Set<String> evaluated = snapshot.evaluatedRuleIds();
            throw new AdvisorViolationException(
                    404, evaluated == null || evaluated.contains(ruleId) ? NO_FINDINGS_MESSAGE : UNKNOWN_RULE_MESSAGE);
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

    /** @param evaluatedRuleIds the rules the report evaluated, or {@code null} when unknown */
    private record Completed<R>(
            R report,
            AdvisorViolationDetailsDto metadata,
            AdvisorViolationCollector.Snapshot index,
            Set<String> evaluatedRuleIds) {}
}
