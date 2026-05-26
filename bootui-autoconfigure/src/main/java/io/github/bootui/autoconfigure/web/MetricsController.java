package io.github.bootui.autoconfigure.web;

import io.github.bootui.core.BootUiDtos.MetricAvailableTagDto;
import io.github.bootui.core.BootUiDtos.MetricDetailDto;
import io.github.bootui.core.BootUiDtos.MetricMeasurementDto;
import io.github.bootui.core.BootUiDtos.MetricMeterDto;
import io.github.bootui.core.BootUiDtos.MetricSampleDto;
import io.github.bootui.core.BootUiDtos.MetricTagDto;
import io.github.bootui.core.BootUiDtos.MetricsReport;
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
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/metrics")
public class MetricsController {

    private static final int MAX_TAG_VALUES = 100;

    private final ObjectProvider<MeterRegistry> registries;

    public MetricsController(ObjectProvider<MeterRegistry> registries) {
        this.registries = registries;
    }

    @GetMapping
    public MetricsReport metrics() {
        MeterRegistry registry = registry();
        if (registry == null) {
            return new MetricsReport(false, 0, List.of());
        }

        Map<String, List<Meter>> metersByName = metersByName(registry.getMeters());
        List<MetricMeterDto> meters = metersByName.entrySet().stream()
                .map(entry -> toMeterDto(entry.getKey(), entry.getValue()))
                .toList();
        return new MetricsReport(true, meters.size(), meters);
    }

    @GetMapping("/detail")
    public MetricDetailDto metric(@RequestParam String name,
                                  @RequestParam(name = "tag", required = false) List<String> tagFilters) {
        MeterRegistry registry = registry();
        if (registry == null) {
            return emptyDetail(false, name);
        }

        List<Meter> meters = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(name))
                .toList();
        if (meters.isEmpty()) {
            return emptyDetail(true, name);
        }

        Map<String, String> requiredTags = parseTagFilters(tagFilters);
        List<Meter> matchingMeters = meters.stream()
                .filter(meter -> hasTags(meter, requiredTags))
                .toList();
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
        try {
            return this.registries.getIfAvailable();
        } catch (NoUniqueBeanDefinitionException ex) {
            return this.registries.orderedStream().findFirst().orElse(null);
        }
    }

    private Map<String, List<Meter>> metersByName(List<Meter> meters) {
        Map<String, List<Meter>> grouped = new TreeMap<>();
        for (Meter meter : meters) {
            grouped.computeIfAbsent(meter.getId().getName(), name -> new ArrayList<>()).add(meter);
        }
        return grouped;
    }

    private MetricMeterDto toMeterDto(String name, List<Meter> meters) {
        return new MetricMeterDto(
                name,
                firstDescription(meters),
                firstBaseUnit(meters),
                firstType(meters),
                availableTags(meters));
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
        SequencedMap<String, String> tags = new LinkedHashMap<>();
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
                valuesByKey.computeIfAbsent(tag.getKey(), key -> new TreeSet<>()).add(tag.getValue());
            }
        }

        List<MetricAvailableTagDto> tags = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> entry : valuesByKey.entrySet()) {
            List<String> values = entry.getValue().stream()
                    .limit(MAX_TAG_VALUES)
                    .toList();
            tags.add(new MetricAvailableTagDto(entry.getKey(), values, entry.getValue().size() > MAX_TAG_VALUES));
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }
}
