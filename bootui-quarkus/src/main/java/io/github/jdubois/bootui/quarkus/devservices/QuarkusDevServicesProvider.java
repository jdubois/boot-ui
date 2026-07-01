package io.github.jdubois.bootui.quarkus.devservices;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.DevServiceDto;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.spi.DevServicesProvider;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Quarkus {@link DevServicesProvider} backed by the build-time-captured {@link QuarkusDevServices} holder.
 *
 * <p>Quarkus exposes Dev Services only at build time, so there is no runtime container list, log tail or
 * restart: services are {@code restartable=false}/{@code logsAvailable=false} and the resource declines the
 * log/restart actions. The deployment processor exposes the synthetic {@code QuarkusDevServices} bean only in
 * non-production launch modes (Dev Services never run in production), so an unsatisfied {@code Instance} means
 * no dev services started — the panel renders empty. Config values (which include JDBC URLs and passwords) are
 * masked here through the same {@code SecretMasker} + {@link QuarkusExposurePolicy} the Configuration panel
 * uses, byte-compatibly with the Spring adapter.</p>
 */
@Singleton
public class QuarkusDevServicesProvider implements DevServicesProvider {

    private static final Pattern URL_CREDENTIALS =
            Pattern.compile("([a-z][a-z0-9+.-]*://)([^:/@\\s]+):([^@\\s]+)@", Pattern.CASE_INSENSITIVE);

    private final Instance<QuarkusDevServices> captured;

    private final QuarkusExposurePolicy exposure;

    private final SecretMasker masker = new SecretMasker();

    @Inject
    public QuarkusDevServicesProvider(Instance<QuarkusDevServices> captured, QuarkusExposurePolicy exposure) {
        this.captured = captured;
        this.exposure = exposure;
    }

    @Override
    public boolean dockerComposePresent() {
        return false;
    }

    @Override
    public boolean testcontainersPresent() {
        return false;
    }

    @Override
    public long snapshotTimestamp() {
        return System.currentTimeMillis();
    }

    @Override
    public List<DevServiceDto> services() {
        if (captured.isUnsatisfied()) {
            return List.of();
        }
        return captured.get().services().stream().map(this::toDto).toList();
    }

    @Override
    public List<String> warnings() {
        return List.of();
    }

    private DevServiceDto toDto(RawDevService raw) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (!raw.containerId().isBlank()) {
            details.put("containerId", raw.containerId());
        }
        if (!raw.description().isBlank()) {
            details.put("description", raw.description());
        }
        raw.config().forEach((key, value) -> details.put(key, displayValue(key, value)));
        return new DevServiceDto(
                "quarkus:" + slug(raw.name()),
                raw.name().isBlank() ? "dev-service" : raw.name(),
                "Dev Service",
                "Quarkus Dev Services",
                raw.description().isBlank() ? null : raw.description(),
                "READY_AT_STARTUP",
                null,
                List.of(),
                details,
                false,
                false,
                "Quarkus Dev Services start throwaway containers in dev/test; this is a build-time snapshot."
                        + " Live logs and restart are managed by Quarkus, not BootUI.");
    }

    private Object displayValue(String key, String value) {
        ValueExposure valueExposure = exposure.valueExposure();
        if (valueExposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (value == null || valueExposure == ValueExposure.FULL || !exposure.maskSecrets()) {
            return value;
        }
        if (masker.isSecret(key)) {
            return SecretMasker.MASKED_VALUE;
        }
        return URL_CREDENTIALS.matcher(value).replaceAll("$1******@");
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "service";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
