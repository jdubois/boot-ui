package io.github.jdubois.bootui.engine.vulnerabilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Interprets package-specific evidence in an advisory returned by a positive OSV Maven query.
 *
 * <p>The query remains the detection authority: even a contradictory or unsupported detail document
 * must retain its finding. This class only selects supported severity and reported upgrade candidates,
 * not whether the installed artifact is safe, reachable, or compatible with an upgrade.
 *
 * <p>Adapters preserve malformed evidence as null list elements (or an {@code Event(null, null)}).
 * Missing lists are empty; a supplied but malformed severity must not become an absent severity.
 */
public final class OsvAdvisoryInterpreter {

    private static final Set<String> EVENT_TYPES = Set.of("introduced", "fixed", "last_affected", "limit");

    private OsvAdvisoryInterpreter() {}

    public record Severity(String type, String score) {}

    /** One OSV event; adapters must reject multi-key or non-string event objects before projection. */
    public record Event(String type, String version) {}

    public record Range(String type, List<Event> events) {
        public Range {
            events = snapshot(events);
        }
    }

    public record Affected(
            String ecosystem, String name, List<String> versions, List<Range> ranges, List<Severity> severity) {
        public Affected {
            versions = snapshot(versions);
            ranges = snapshot(ranges);
            severity = snapshot(severity);
        }
    }

    /**
     * Selected evidence, never an unaffected verdict. {@code unresolved} signals missing, contradictory,
     * malformed, or unsupported package/version interpretation. Fixed versions are newer reported
     * candidates verified against the supplied matching evidence, not guaranteed upgrades.
     */
    public record Result(String severity, Double cvssScore, List<String> fixedVersions, boolean unresolved) {
        public Result {
            fixedVersions = List.copyOf(fixedVersions);
        }
    }

    public static Result interpret(
            String packageName,
            String installedVersion,
            List<Affected> affected,
            List<Severity> severity,
            String databaseSeverity) {
        List<Entry> matching = new ArrayList<>();
        boolean unknownIdentity = false;
        for (Affected item : snapshot(affected)) {
            if (item == null || blank(item.ecosystem()) || blank(item.name())) {
                unknownIdentity = true;
            } else if (!blank(packageName)
                    && "Maven".equals(item.ecosystem())
                    && (item.name().equals(packageName) || "*".equals(item.name()))) {
                matching.add(new Entry(item));
            }
        }

        boolean applicable = false;
        boolean unresolved = unknownIdentity;
        boolean packageSeveritySupplied = false;
        Double score = null;
        List<String> candidates = new ArrayList<>();
        for (Entry entry : matching) {
            Evaluation evaluation = entry.evaluate(installedVersion);
            unresolved |= evaluation.unresolved();
            if (evaluation.applicability() == Applicability.MATCHED) {
                applicable = true;
                packageSeveritySupplied |= !entry.affected.severity().isEmpty();
                score = maximum(score, highestScore(entry.affected.severity()));
                for (Timeline timeline : entry.timelines) {
                    String candidate = timeline.closingFix(installedVersion);
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        unresolved |= !applicable;
        if (!packageSeveritySupplied) {
            score = highestScore(snapshot(severity));
        }

        List<String> verified = new ArrayList<>();
        for (String candidate : candidates) {
            if (!DependencyReports.fixAvailable(installedVersion, List.of(candidate))) {
                continue;
            }
            boolean unaffected = !matching.isEmpty();
            for (Entry entry : matching) {
                if (entry.evaluate(candidate).applicability() != Applicability.NOT_MATCHED) {
                    unaffected = false;
                    break;
                }
            }
            // Missing package identity might describe the proposed target; it cannot verify an upgrade.
            if (unaffected && !unknownIdentity) {
                verified.add(candidate);
            }
        }
        return new Result(
                score == null
                        ? DependencyReports.normalizeSeverity(databaseSeverity)
                        : DependencyReports.normalizeSeverity(score),
                score,
                DependencyReports.orderFixedVersions(verified, 10),
                unresolved);
    }

    private enum Applicability {
        MATCHED,
        NOT_MATCHED,
        UNRESOLVED
    }

    private record Evaluation(Applicability applicability, boolean unresolved) {}

    private static final class Entry {
        private final Affected affected;
        private final List<Timeline> timelines;

        private Entry(Affected affected) {
            this.affected = affected;
            this.timelines = affected.ranges().stream().map(Timeline::new).toList();
        }

        private Evaluation evaluate(String version) {
            if (blank(version)) {
                return new Evaluation(Applicability.UNRESOLVED, true);
            }
            boolean matched = false;
            boolean unresolved = affected.versions().isEmpty() && timelines.isEmpty();
            for (String listed : affected.versions()) {
                if (blank(listed)) {
                    unresolved = true;
                } else if (version.equals(listed)) {
                    matched = true;
                }
            }
            for (Timeline timeline : timelines) {
                Applicability applicability = timeline.evaluate(version);
                matched |= applicability == Applicability.MATCHED;
                unresolved |= applicability == Applicability.UNRESOLVED;
            }
            return new Evaluation(
                    matched ? Applicability.MATCHED : unresolved ? Applicability.UNRESOLVED : Applicability.NOT_MATCHED,
                    unresolved);
        }
    }

    private static final class Timeline {
        private final List<Event> events = new ArrayList<>();
        private final List<String> limits = new ArrayList<>();
        private boolean valid;

        private Timeline(Range range) {
            if (range == null
                    || !"ECOSYSTEM".equals(range.type())
                    || range.events().isEmpty()) {
                return;
            }
            boolean introduced = false;
            boolean fixed = false;
            boolean lastAffected = false;
            for (Event event : range.events()) {
                if (event == null
                        || event.type() == null
                        || !EVENT_TYPES.contains(event.type())
                        || blank(event.version())) {
                    return;
                }
                introduced |= "introduced".equals(event.type());
                fixed |= "fixed".equals(event.type());
                lastAffected |= "last_affected".equals(event.type());
                if ("limit".equals(event.type())) {
                    limits.add(event.version());
                } else {
                    events.add(event);
                }
            }
            if (!introduced || fixed && lastAffected) {
                return;
            }
            events.sort((left, right) -> {
                if (beginning(left) != beginning(right)) {
                    return beginning(left) ? -1 : 1;
                }
                int compared = MavenVersionComparator.compare(left.version(), right.version());
                if (compared == 0) {
                    // Inclusive singleton intervals close after their equal introduction, in either input order.
                    return Boolean.compare("last_affected".equals(left.type()), "last_affected".equals(right.type()));
                }
                return compared;
            });
            for (int i = 1; i < events.size(); i++) {
                Event previous = events.get(i - 1);
                Event current = events.get(i);
                if (!previous.type().equals(current.type())
                        && !("introduced".equals(previous.type()) && "last_affected".equals(current.type()))
                        && beginning(previous) == beginning(current)
                        && MavenVersionComparator.compare(previous.version(), current.version()) == 0) {
                    // Contradictory status changes at one Maven-equivalent boundary have no unique order.
                    return;
                }
            }
            valid = true;
        }

        private Applicability evaluate(String version) {
            if (!valid || blank(version)) {
                return Applicability.UNRESOLVED;
            }
            if (!limits.isEmpty()
                    && limits.stream()
                            .noneMatch(
                                    limit -> "*".equals(limit) || MavenVersionComparator.compare(version, limit) < 0)) {
                return Applicability.NOT_MATCHED;
            }
            boolean affected = false;
            for (Event event : events) {
                int compared = beginning(event) ? 1 : MavenVersionComparator.compare(version, event.version());
                if ("introduced".equals(event.type()) && compared >= 0) {
                    affected = true;
                } else if ("fixed".equals(event.type()) && compared >= 0
                        || "last_affected".equals(event.type()) && compared > 0) {
                    affected = false;
                }
            }
            return affected ? Applicability.MATCHED : Applicability.NOT_MATCHED;
        }

        private String closingFix(String version) {
            if (evaluate(version) != Applicability.MATCHED) {
                return null;
            }
            for (Event event : events) {
                if ("fixed".equals(event.type()) && MavenVersionComparator.compare(version, event.version()) < 0) {
                    return event.version();
                }
            }
            return null;
        }
    }

    private static boolean beginning(Event event) {
        return "introduced".equals(event.type()) && "0".equals(event.version());
    }

    private static Double highestScore(List<Severity> severity) {
        Double score = null;
        for (Severity assessment : severity) {
            if (assessment != null) {
                score = maximum(score, DependencyReports.parseScore(assessment.type(), assessment.score()));
            }
        }
        return score;
    }

    private static Double maximum(Double left, Double right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : Math.max(left, right);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> snapshot(List<T> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
