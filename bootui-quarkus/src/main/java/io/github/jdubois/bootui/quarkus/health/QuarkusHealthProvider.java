package io.github.jdubois.bootui.quarkus.health;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.spi.HealthProvider;
import io.smallrye.health.SmallRyeHealth;
import io.smallrye.health.SmallRyeHealthReporter;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quarkus {@link HealthProvider} over SmallRye Health: it reads the aggregated MicroProfile Health report
 * in-process (no HTTP, no {@code /q/health} round trip) via {@link SmallRyeHealthReporter} and maps it onto
 * BootUI's neutral {@link HealthNodeDto}, exactly as the Spring adapter maps Actuator's {@code HealthEndpoint}.
 *
 * <p>This is the sole SmallRye-importing type in the Quarkus adapter; it is produced by
 * {@code BootUiHealthProducer}, which the deployment processor excludes from bean discovery when
 * {@code quarkus-smallrye-health} is absent (R2). When the backend is absent there is no provider, and the
 * engine {@code HealthService} renders the DISABLED root with {@code QuarkusHealthGuidance}'s setup steps.</p>
 *
 * <p>MicroProfile Health is a flat report — a top-level {@code status} plus a {@code checks} array, each entry
 * a {@code {name, status, data?}} — so the mapped tree is one level deep (the SmallRye checks become the root's
 * {@code components}; checks never nest). The mapper is deliberately defensive: a sparse or unexpected payload
 * yields a best-effort {@code UNKNOWN}/empty result rather than throwing, so a well-formed-but-minimal report
 * can never 500 the panel. A failure of {@link SmallRyeHealthReporter#getHealth()} itself propagates (mirroring
 * the Spring provider, which does not fail-soft either).</p>
 */
public final class QuarkusHealthProvider implements HealthProvider {

    private final SmallRyeHealthReporter reporter;

    public QuarkusHealthProvider(SmallRyeHealthReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public HealthNodeDto readRoot() {
        return map(reporter.getHealth());
    }

    /**
     * Maps a {@link SmallRyeHealth} report onto the neutral health tree. Package-private and {@code static} so
     * it can be unit-tested against a hand-built {@link SmallRyeHealth} without a CDI container or a backend.
     */
    static HealthNodeDto map(SmallRyeHealth health) {
        JsonObject payload = health.getPayload();
        String status = payload.getString("status", "UNKNOWN");
        List<HealthNodeDto> components = new ArrayList<>();
        JsonArray checks = payload.getJsonArray("checks");
        if (checks != null) {
            for (JsonValue value : checks) {
                if (value.getValueType() == JsonValue.ValueType.OBJECT) {
                    components.add(toNode(value.asJsonObject()));
                }
            }
        }
        return new HealthNodeDto("application", status, null, List.copyOf(components));
    }

    private static HealthNodeDto toNode(JsonObject check) {
        String name = check.getString("name", "unknown");
        String status = check.getString("status", "UNKNOWN");
        Object data = check.containsKey("data") && check.get("data").getValueType() == JsonValue.ValueType.OBJECT
                ? toMap(check.getJsonObject("data"))
                : null;
        return new HealthNodeDto(name, status, data, List.of());
    }

    private static Map<String, Object> toMap(JsonObject data) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : data.entrySet()) {
            map.put(entry.getKey(), toValue(entry.getValue()));
        }
        return map;
    }

    private static Object toValue(JsonValue value) {
        return switch (value.getValueType()) {
            case STRING -> ((JsonString) value).getString();
            case NUMBER -> {
                JsonNumber number = (JsonNumber) value;
                yield number.isIntegral() ? (Object) number.longValue() : (Object) number.doubleValue();
            }
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            case NULL -> null;
            default -> value.toString();
        };
    }
}
