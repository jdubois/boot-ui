package io.github.jdubois.bootui.engine.metrics;

import io.github.jdubois.bootui.core.dto.MetricAvailableTagDto;
import io.github.jdubois.bootui.core.dto.MetricDetailDto;
import io.github.jdubois.bootui.core.dto.MetricMeasurementDto;
import io.github.jdubois.bootui.core.dto.MetricMeterDto;
import io.github.jdubois.bootui.core.dto.MetricSampleDto;
import io.github.jdubois.bootui.core.dto.MetricTagDto;
import io.github.jdubois.bootui.core.dto.MetricsReport;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Framework-neutral Micrometer reporting service backing the Metrics panel.
 *
 * <p>The host adapter supplies two seams: a {@link Supplier} that resolves the live
 * {@link MeterRegistry} (or {@code null} when none is available, e.g. metrics disabled) on every
 * call, and a {@link Predicate} that decides whether a given {@link Meter} should be visible (the
 * Spring adapter feeds BootUI's own self-data filter so the console never reports its own traffic).
 * Micrometer is intentionally a direct dependency: it is the framework-neutral metrics API used by
 * both Spring Boot and Quarkus, so no extra abstraction is warranted.
 */
public class MetricsReportProvider {

    private static final int MAX_TAG_VALUES = 100;

    private final Supplier<MeterRegistry> registrySupplier;

    private final Predicate<Meter> meterFilter;

    public MetricsReportProvider(Supplier<MeterRegistry> registrySupplier, Predicate<Meter> meterFilter) {
        this.registrySupplier = registrySupplier;
        this.meterFilter = meterFilter;
    }

    public MetricsReport metrics() {
        MeterRegistry registry = registry();
        if (registry == null) {
            return new MetricsReport(false, 0, List.of());
        }

        Map<String, List<Meter>> metersByName = metersByName(visibleMeters(registry.getMeters()));
        List<MetricMeterDto> meters = metersByName.entrySet().stream()
                .map(entry -> toMeterDto(entry.getKey(), entry.getValue()))
                .toList();
        return new MetricsReport(true, meters.size(), meters);
    }

    public MetricDetailDto metric(String name, List<String> tagFilters) {
        MeterRegistry registry = registry();
        if (registry == null) {
            return emptyDetail(false, name);
        }

        List<Meter> meters = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(name))
                .filter(meterFilter)
                .toList();
        if (meters.isEmpty()) {
            return emptyDetail(true, name);
        }

        Map<String, String> requiredTags = parseTagFilters(tagFilters);
        List<Meter> matchingMeters =
                meters.stream().filter(meter -> hasTags(meter, requiredTags)).toList();
        List<MetricSampleDto> samples = matchingMeters.stream()
                .map(this::toSample)
                .sorted(Comparator.comparing(sample -> sample.tags().toString()))
                .toList();

        return new MetricDetailDto(
                true,
                name,
                firstDescription(meters),
                firstBaseUnit(meters),
                firstType(meters),
                aggregateMeasurements(matchingMeters),
                availableTags(meters),
                samples);
    }

    private MeterRegistry registry() {
        return registrySupplier.get();
    }

    private Map<String, List<Meter>> metersByName(List<Meter> meters) {
        Map<String, List<Meter>> grouped = new TreeMap<>();
        for (Meter meter : meters) {
            grouped.computeIfAbsent(meter.getId().getName(), name -> new ArrayList<>())
                    .add(meter);
        }
        return grouped;
    }

    private List<Meter> visibleMeters(List<Meter> meters) {
        return meters.stream().filter(meterFilter).toList();
    }

    private MetricMeterDto toMeterDto(String name, List<Meter> meters) {
        return new MetricMeterDto(
                name, firstDescription(meters), firstBaseUnit(meters), firstType(meters), availableTags(meters));
    }

    private MetricDetailDto emptyDetail(boolean metricsAvailable, String name) {
        return new MetricDetailDto(metricsAvailable, name, null, null, null, List.of(), List.of(), List.of());
    }

    private String firstDescription(List<Meter> meters) {
        return meters.stream()
                .map(meter -> meter.getId().getDescription())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String firstBaseUnit(List<Meter> meters) {
        return meters.stream()
                .map(meter -> meter.getId().getBaseUnit())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String firstType(List<Meter> meters) {
        return meters.stream()
                .map(meter -> meter.getId().getType())
                .filter(type -> type != null)
                .map(Enum::name)
                .findFirst()
                .orElse(null);
    }

    private Map<String, String> parseTagFilters(List<String> tagFilters) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagFilters == null) {
            return tags;
        }
        for (String tagFilter : tagFilters) {
            int separator = tagFilter == null ? -1 : tagFilter.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException("Metric tag filters must use key:value syntax");
            }
            tags.put(tagFilter.substring(0, separator), tagFilter.substring(separator + 1));
        }
        return tags;
    }

    private boolean hasTags(Meter meter, Map<String, String> requiredTags) {
        for (Map.Entry<String, String> requiredTag : requiredTags.entrySet()) {
            if (!requiredTag.getValue().equals(meter.getId().getTag(requiredTag.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private List<MetricAvailableTagDto> availableTags(List<Meter> meters) {
        Map<String, TreeSet<String>> valuesByKey = new TreeMap<>();
        for (Meter meter : meters) {
            for (Tag tag : meter.getId().getTags()) {
                valuesByKey
                        .computeIfAbsent(tag.getKey(), key -> new TreeSet<>())
                        .add(tag.getValue());
            }
        }

        List<MetricAvailableTagDto> tags = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> entry : valuesByKey.entrySet()) {
            List<String> values =
                    entry.getValue().stream().limit(MAX_TAG_VALUES).toList();
            tags.add(new MetricAvailableTagDto(
                    entry.getKey(), values, entry.getValue().size() > MAX_TAG_VALUES));
        }
        return tags;
    }

    private MetricSampleDto toSample(Meter meter) {
        List<MetricTagDto> tags = meter.getId().getTags().stream()
                .sorted(Comparator.comparing(Tag::getKey).thenComparing(Tag::getValue))
                .map(tag -> new MetricTagDto(tag.getKey(), tag.getValue()))
                .toList();
        return new MetricSampleDto(tags, measurements(meter));
    }

    private List<MetricMeasurementDto> aggregateMeasurements(List<Meter> meters) {
        Map<Statistic, Double> valuesByStatistic = new TreeMap<>(Comparator.comparing(Enum::name));
        for (Meter meter : meters) {
            for (Measurement measurement : meter.measure()) {
                double value = measurement.getValue();
                if (!Double.isFinite(value)) {
                    continue;
                }
                Statistic statistic = measurement.getStatistic();
                valuesByStatistic.merge(statistic, value, statistic == Statistic.MAX ? Math::max : Double::sum);
            }
        }
        return toMeasurements(valuesByStatistic);
    }

    private List<MetricMeasurementDto> measurements(Meter meter) {
        Map<Statistic, Double> valuesByStatistic = new TreeMap<>(Comparator.comparing(Enum::name));
        for (Measurement measurement : meter.measure()) {
            double value = measurement.getValue();
            if (Double.isFinite(value)) {
                valuesByStatistic.put(measurement.getStatistic(), value);
            }
        }
        return toMeasurements(valuesByStatistic);
    }

    private List<MetricMeasurementDto> toMeasurements(Map<Statistic, Double> valuesByStatistic) {
        List<MetricMeasurementDto> measurements = new ArrayList<>();
        for (Map.Entry<Statistic, Double> entry : valuesByStatistic.entrySet()) {
            measurements.add(new MetricMeasurementDto(entry.getKey().getTagValueRepresentation(), entry.getValue()));
        }
        return measurements;
    }
}
