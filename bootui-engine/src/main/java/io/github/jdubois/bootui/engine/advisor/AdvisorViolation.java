package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * One counted finding: its text and, when the scan evidence names exactly one code element, its location.
 * Text and location travel together through sanitization, sampling, retention, and paging, so the two
 * lists an advisor publishes can never drift apart.
 *
 * <p>The identity is the finding's canonical, structure-derived key ({@link AdvisorFindingIdentity}); it is never
 * derived from the display text and is never sanitized. A finding without one is "unkeyed": counted, but neither
 * diffed between scans nor suppressible.
 *
 * @param text the finding text, sanitized once it has been recorded (raw text may be {@code null} before then)
 * @param location the code element the finding points at, or {@code null} when it has none
 * @param identity the canonical identity of the finding, or {@code null} when its rule provides none
 */
public record AdvisorViolation(String text, AdvisorViolationLocationDto location, String identity) {

    public AdvisorViolation(String text, AdvisorViolationLocationDto location) {
        this(text, location, null);
    }

    public static AdvisorViolation of(String text) {
        return new AdvisorViolation(text, null, null);
    }

    public static AdvisorViolation of(String text, String identity) {
        return new AdvisorViolation(text, null, identity);
    }

    public AdvisorViolation withIdentity(String identity) {
        return new AdvisorViolation(text, location, identity);
    }

    AdvisorViolation sanitized(UnaryOperator<String> sanitizer) {
        return new AdvisorViolation(
                Objects.requireNonNull(sanitizer.apply(text), "Advisor violation sanitizer must return non-null text."),
                location,
                identity);
    }

    AdvisorViolation withLocation(AdvisorViolationLocationDto location) {
        return new AdvisorViolation(text, location, identity);
    }

    /** The texts of {@code violations}, in order. */
    public static List<String> texts(List<AdvisorViolation> violations) {
        List<String> texts = new ArrayList<>(violations.size());
        for (AdvisorViolation violation : violations) texts.add(violation.text());
        return List.copyOf(texts);
    }

    /**
     * The identities of {@code violations}, aligned index-for-index with {@link #texts}, or an empty list when
     * none of them has one.
     */
    public static List<String> identities(List<AdvisorViolation> violations) {
        List<String> identities = new ArrayList<>(violations.size());
        boolean any = false;
        for (AdvisorViolation violation : violations) {
            identities.add(violation.identity());
            any |= violation.identity() != null;
        }
        return any ? java.util.Collections.unmodifiableList(identities) : List.of();
    }

    /**
     * The locations of {@code violations}, aligned index-for-index with {@link #texts}, or an empty list when
     * none of them has a location.
     */
    public static List<AdvisorViolationLocationDto> locations(List<AdvisorViolation> violations) {
        List<AdvisorViolationLocationDto> locations = new ArrayList<>(violations.size());
        boolean any = false;
        for (AdvisorViolation violation : violations) {
            locations.add(violation.location());
            any |= violation.location() != null;
        }
        return any ? java.util.Collections.unmodifiableList(locations) : List.of();
    }

    /**
     * A lazy view of plain texts as findings without locations. Nothing is copied, so a caller that reads only
     * the budgeted head of a very long list never traverses its tail.
     */
    public static List<AdvisorViolation> withoutLocations(List<String> texts) {
        return new java.util.AbstractList<>() {
            @Override
            public AdvisorViolation get(int index) {
                return of(texts.get(index));
            }

            @Override
            public int size() {
                return texts.size();
            }
        };
    }
}
