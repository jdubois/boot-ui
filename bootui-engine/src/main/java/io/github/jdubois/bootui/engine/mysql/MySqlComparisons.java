package io.github.jdubois.bootui.engine.mysql;

import io.github.jdubois.bootui.core.dto.MySqlChangeDto;
import io.github.jdubois.bootui.core.dto.MySqlMetricDto;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded server-counter baselines only; gauges, unavailable values and incomparable resets are excluded. */
final class MySqlComparisons {
    private static final Set<String> COUNTERS = Set.of(
            "Connections",
            "Aborted_connects",
            "Innodb_buffer_pool_reads",
            "Innodb_buffer_pool_read_requests",
            "Innodb_buffer_pool_wait_free",
            "Innodb_row_lock_waits",
            "Innodb_row_lock_time",
            "Innodb_log_waits");
    private final Map<String, Baseline> baselines = new HashMap<>();

    List<MySqlChangeDto> observe(String key, String server, MySqlCounterSample sample) {
        if (server == null || sample == null || sample.observedAt() < sample.startedAt()) {
            return List.of();
        }
        long now = sample.observedAt();
        String uptimeText = sample.metrics().stream()
                .filter(metric -> "Uptime".equals(metric.id()))
                .map(MySqlMetricDto::value)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (uptimeText == null) {
            return List.of();
        }
        BigInteger uptime = new BigInteger(uptimeText);
        BigInteger elapsed = uptime.multiply(BigInteger.valueOf(1000));
        // Uptime is integer seconds and may be sampled anywhere during the status query.
        BigInteger earliestBoot =
                BigInteger.valueOf(sample.startedAt()).subtract(elapsed).subtract(BigInteger.valueOf(999));
        BigInteger latestBoot = BigInteger.valueOf(now).subtract(elapsed);
        Baseline baseline = baselines.get(key);
        String identity = server + "\u0000" + sample.schemaName();
        boolean reset = baseline == null
                || !identity.equals(baseline.server)
                || uptime.compareTo(baseline.uptime) < 0
                || earliestBoot.compareTo(baseline.latestBoot.add(BigInteger.valueOf(3000))) > 0
                || latestBoot.compareTo(baseline.earliestBoot.subtract(BigInteger.valueOf(3000))) < 0;
        if (!reset) {
            for (MySqlMetricDto metric : sample.metrics()) {
                Observation old = baseline.metrics.get(metric.id());
                if (old != null
                        && metric.value() != null
                        && COUNTERS.contains(metric.id())
                        && new BigInteger(metric.value()).compareTo(old.value) < 0) {
                    reset = true;
                    break;
                }
            }
        }
        if (reset) {
            baseline = new Baseline(identity, uptime, earliestBoot, latestBoot);
            baselines.put(key, baseline);
        }
        List<MySqlChangeDto> changes = new ArrayList<>();
        for (MySqlMetricDto metric : sample.metrics()) {
            if (!COUNTERS.contains(metric.id()) || metric.value() == null) {
                continue;
            }
            BigInteger current = new BigInteger(metric.value());
            Observation old = baseline.metrics.get(metric.id());
            if (old != null && now > old.at) {
                changes.add(new MySqlChangeDto(
                        metric.id(),
                        metric.scope(),
                        metric.unit(),
                        current.subtract(old.value).toString(),
                        old.at,
                        now,
                        "Status-query observation times, same observed server and no detected restart/counter"
                                + " decrease; independent resets remain possible."));
            }
            baseline.metrics.put(metric.id(), new Observation(current, now));
        }
        baseline.uptime = uptime;
        return List.copyOf(changes);
    }

    void retain(Set<String> keys) {
        baselines.keySet().retainAll(keys);
    }

    void clear() {
        baselines.clear();
    }

    private static final class Baseline {
        final String server;
        BigInteger uptime;
        final BigInteger earliestBoot;
        final BigInteger latestBoot;
        final Map<String, Observation> metrics = new HashMap<>();

        Baseline(String server, BigInteger uptime, BigInteger earliestBoot, BigInteger latestBoot) {
            this.server = server;
            this.uptime = uptime;
            this.earliestBoot = earliestBoot;
            this.latestBoot = latestBoot;
        }
    }

    private record Observation(BigInteger value, long at) {}
}
