package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.AdvisorChangesDto;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationDetailsDto;
import io.github.jdubois.bootui.core.dto.DismissedFindingDto;
import io.github.jdubois.bootui.engine.support.PagedList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p>An advisor whose findings carry a canonical identity can opt in with {@link #trackingChanges()}. Each publish
 * then compares its retained findings with the baseline, the last scan that concluded at least one rule, and
 * publishes the result as {@link AdvisorViolationDetailsDto#changes()}. The comparison is computed once and the
 * older index is dropped, so tracking costs no memory beyond the single snapshot, except while a scan that
 * concluded nothing (an outage) sits in front of the baseline it did not replace.</p>
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
    private volatile boolean trackChanges;
    private Baseline baseline;

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

    /** Compares every published scan with the previous comparable one; for advisors whose findings are keyed. */
    public AdvisorScanState<R> trackingChanges() {
        this.trackChanges = true;
        return this;
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

    public synchronized R publish(R report, AdvisorViolationCollector collector) {
        Objects.requireNonNull(report, "Advisor report is required.");
        Objects.requireNonNull(collector, "Advisor violation collector is required.");
        AdvisorViolationCollector.Snapshot index = collector.snapshot();
        String scanId = UUID.randomUUID().toString();
        AdvisorChangesDto changes = null;
        Map<String, List<String>> markers = Map.of();
        if (trackChanges) {
            Baseline previous = baseline;
            if (!index.coverage().informative()) {
                // The very first scan has nothing to compare; a later one that concluded nothing says so.
                changes = previous == null && completed == null
                        ? null
                        : AdvisorScanDiff.notCompared(previous == null ? null : previous.scanId(), index.coverage());
            } else {
                if (previous != null) {
                    AdvisorScanDiff.Result diff = AdvisorScanDiff.compare(previous.scanId(), previous.index(), index);
                    changes = diff.changes();
                    markers = diff.markers();
                }
                baseline = new Baseline(scanId, index);
            }
        }
        AdvisorViolationDetailsDto metadata = new AdvisorViolationDetailsDto(
                scanId,
                index.total(),
                index.retained(),
                index.retentionLimit(),
                index.retained() < index.total(),
                index.locationNotes(),
                changes);
        R published = Objects.requireNonNull(
                withMetadata.apply(report, metadata), "Advisor metadata projection must return a report.");
        Collection<String> catalog = ruleCatalog.get();
        completed = new Completed<>(published, metadata, index, catalog == null ? null : Set.copyOf(catalog), markers);
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
        return ruleViolations(ruleId, scanId, offset, limit, List.of());
    }

    /**
     * The suppression of the completed scan {@code scanId} names, or a projection that suppresses nothing when that
     * scan is no longer (or not yet) the current one. Rule-level dismissal takes precedence, so findings of a rule in
     * {@code dismissedRules} are never counted as suppressed.
     *
     * @param dismissed every finding the developer dismissed, of any advisor
     * @param rulePrefix the prefix of this advisor's rule ids, so that a dismissed finding of a rule it no longer has
     *     is still listed as such; {@code null} to list only findings of the rules it has
     */
    public AdvisorSuppression suppression(
            String scanId, Set<String> dismissedRules, Collection<DismissedFindingDto> dismissed, String rulePrefix) {
        Completed<R> snapshot = completed;
        boolean current = snapshot != null
                && scanId != null
                && scanId.equals(snapshot.metadata().scanId());
        return suppressionOf(current ? snapshot : null, dismissedRules, dismissed, rulePrefix);
    }

    /**
     * The latest report together with its suppression, both taken from one snapshot of the latest completed scan, so a
     * scan that completes meanwhile can never pair a report with another scan's retained findings.
     */
    public Current<R> current(
            Supplier<R> initial,
            Set<String> dismissedRules,
            Collection<DismissedFindingDto> dismissed,
            String rulePrefix) {
        Completed<R> snapshot = completed;
        if (snapshot == null) {
            R report = currentReport(initial);
            snapshot = completed;
            if (snapshot == null) {
                return new Current<>(report, suppressionOf(null, dismissedRules, dismissed, rulePrefix));
            }
        }
        return new Current<>(snapshot.report(), suppressionOf(snapshot, dismissedRules, dismissed, rulePrefix));
    }

    /** A report and what the dismissed findings do to it. */
    public record Current<R>(R report, AdvisorSuppression suppression) {}

    private AdvisorSuppression suppressionOf(
            Completed<R> snapshot,
            Set<String> dismissedRules,
            Collection<DismissedFindingDto> dismissed,
            String rulePrefix) {
        Collection<String> known = snapshot != null ? snapshot.ruleCatalog() : ruleCatalog.get();
        Set<String> catalog = known == null ? null : Set.copyOf(known);
        List<DismissedFindingDto> belonging = new ArrayList<>();
        for (DismissedFindingDto finding : dismissed) {
            if ((catalog != null && catalog.contains(finding.ruleId()))
                    || (rulePrefix != null && finding.ruleId().startsWith(rulePrefix))) {
                belonging.add(finding);
            }
        }
        return snapshot != null
                ? AdvisorSuppression.of(snapshot.index(), catalog, dismissedRules, belonging)
                : AdvisorSuppression.unverified(belonging, catalog);
    }

    /**
     * One page of retained findings, each flagged when it is among the {@code dismissed} findings. Every retained
     * finding is still returned, so a dismissed one can be restored, and {@code violationCount} stays the scan's total.
     */
    public AdvisorRuleViolationsDto ruleViolations(
            String ruleId, String scanId, Integer offset, Integer limit, Collection<DismissedFindingDto> dismissed) {
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
        // Text, location and identity are cut from the same retained records, so the lists stay aligned on every page.
        List<String> markers = snapshot.changes().get(ruleId);
        List<String> pageChanges = markers == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(markers.subList(
                        page.page().offset(),
                        page.page().offset() + page.items().size())));
        Set<String> dismissedIdentities = new HashSet<>();
        for (DismissedFindingDto finding : dismissed) {
            if (finding.ruleId().equals(ruleId)) dismissedIdentities.add(finding.identity());
        }
        int suppressedTotal = 0;
        for (AdvisorViolation violation : rule.violations()) {
            if (violation.identity() != null && dismissedIdentities.contains(violation.identity())) suppressedTotal++;
        }
        List<Boolean> suppressed = new ArrayList<>(page.items().size());
        boolean anySuppressed = false;
        for (AdvisorViolation violation : page.items()) {
            boolean flag = violation.identity() != null && dismissedIdentities.contains(violation.identity());
            suppressed.add(flag);
            anySuppressed |= flag;
        }
        return new AdvisorRuleViolationsDto(
                snapshot.metadata().scanId(),
                ruleId,
                rule.violationCount(),
                rule.violations().size(),
                rule.violations().size() < rule.violationCount(),
                AdvisorViolation.texts(page.items()),
                page.page(),
                AdvisorViolation.locations(page.items()),
                AdvisorViolation.identities(page.items()),
                pageChanges,
                anySuppressed ? Collections.unmodifiableList(suppressed) : List.of(),
                suppressedTotal);
    }

    /**
     * @param ruleCatalog the advisor's rules when the report was published, or {@code null} when unknown
     * @param changes per rule, one marker for each retained finding, aligned with the retained list
     */
    private record Completed<R>(
            R report,
            AdvisorViolationDetailsDto metadata,
            AdvisorViolationCollector.Snapshot index,
            Set<String> ruleCatalog,
            Map<String, List<String>> changes) {}

    /** The last scan that concluded at least one rule, which the next scan is compared with. */
    private record Baseline(String scanId, AdvisorViolationCollector.Snapshot index) {}
}
