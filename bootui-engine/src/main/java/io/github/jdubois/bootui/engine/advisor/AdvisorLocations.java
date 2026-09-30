package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Completes the violation locations of one explicit scan in a single pass over the rule samples and the
 * retained details together, so both carry the same source path and verified line for the same finding.
 */
public final class AdvisorLocations {

    /**
     * @param mapper completes one location produced during the same scan, without changing its identity
     * @param notes why some locations carry no source path
     */
    public record Resolution(UnaryOperator<AdvisorViolationLocationDto> mapper, List<String> notes) {
        public Resolution {
            Objects.requireNonNull(mapper, "Location mapper is required.");
            notes = List.copyOf(notes);
        }
    }

    private AdvisorLocations() {}

    /**
     * Resolves every location in {@code results}' samples and in {@code collector}'s retained details once, maps
     * both through the same resolution, and records its notes. A failed resolution keeps each location's class and
     * member but drops its line, which could not be verified, and records why.
     *
     * @return {@code results} with their completed sample locations, in the same order
     */
    public static <R> List<R> complete(
            AdvisorViolationCollector collector,
            List<R> results,
            Function<R, List<AdvisorViolationLocationDto>> sampleLocations,
            BiFunction<R, List<AdvisorViolationLocationDto>, R> withSampleLocations,
            Function<List<AdvisorViolationLocationDto>, Resolution> resolver) {
        List<AdvisorViolationLocationDto> located = new ArrayList<>();
        collector.forEachLocation(located::add);
        results.forEach(result ->
                sampleLocations.apply(result).stream().filter(Objects::nonNull).forEach(located::add));
        if (located.isEmpty()) return results;
        Resolution resolution;
        try {
            resolution = Objects.requireNonNull(resolver.apply(located), "Location resolution is required.");
        } catch (RuntimeException | LinkageError ex) {
            resolution = new Resolution(
                    AdvisorViolationLocationDto::withoutLine,
                    List.of("Source lookup failed (" + ex.getClass().getSimpleName()
                            + "); locations keep no source path or line."));
        }
        UnaryOperator<AdvisorViolationLocationDto> mapper = resolution.mapper();
        collector.mapLocations(mapper);
        collector.addLocationNotes(resolution.notes());
        List<R> completed = new ArrayList<>(results.size());
        for (R result : results) {
            List<AdvisorViolationLocationDto> locations = sampleLocations.apply(result);
            if (locations.isEmpty()) {
                completed.add(result);
                continue;
            }
            List<AdvisorViolationLocationDto> mapped = new ArrayList<>(locations.size());
            for (AdvisorViolationLocationDto location : locations) {
                mapped.add(location == null ? null : mapper.apply(location));
            }
            completed.add(withSampleLocations.apply(result, mapped));
        }
        return completed;
    }
}
