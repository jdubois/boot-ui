package io.github.jdubois.bootui.quarkus.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.BindingDiagnosticDto;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuarkusBindingDiagnosticsExtractorTest {

    private final QuarkusBindingDiagnosticsExtractor extractor = new QuarkusBindingDiagnosticsExtractor();

    @Test
    void extractsJackson2InvalidFormatAndUnrecognizedPropertyAndScrubsUnderMaskedAndMetadataOnly() {
        InvalidFormatException invalidFormat = InvalidFormatException.from(
                null,
                "Cannot deserialize value of type `java.time.LocalDate` from String \"2026-99-99-secret\": Failed",
                "2026-99-99-secret",
                LocalDate.class);
        invalidFormat.prependPath(Map.class, "secretMapKey");
        invalidFormat.prependPath(List.class, 2);
        invalidFormat.prependPath(CreateProductRequest.class, "schedules");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        UnrecognizedPropertyException unrecognized = org.assertj.core.api.Assertions.catchThrowableOfType(
                UnrecognizedPropertyException.class,
                () -> mapper.readValue("{\"secretInternalToken\":\"top-secret\"}", CreateProductRequest.class));

        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.setBindingDiagnosticExtractor(extractor);
        store.record(invalidFormat, "vert.x-worker-1", "POST", "/api/products", "ProductResource#create", "web");
        store.record(unrecognized, "vert.x-worker-2", "POST", "/api/products", "ProductResource#create", "web");

        for (ValueExposure mode : List.of(ValueExposure.MASKED, ValueExposure.METADATA_ONLY)) {
            ExceptionsService service = new ExceptionsService(new ExposurePolicy() {
                @Override
                public ValueExposure valueExposure() {
                    return mode;
                }

                @Override
                public boolean maskSecrets() {
                    return true;
                }
            });
            for (var group : store.groups()) {
                ExceptionDetailDto detail = service.detail(store.find(group.fingerprint()));
                assertThat(detail.toString())
                        .as("zero rejected-value or unknown-property leakage under %s", mode)
                        .doesNotContain("2026-99-99-secret", "secretMapKey", "secretInternalToken");
            }

            ExceptionDetailDto invalidDetail =
                    service.detail(store.find(findFingerprint(store, InvalidFormatException.class.getName())));
            assertThat(invalidDetail.occurrences().get(0).bindingDiagnostics())
                    .containsExactly(new BindingDiagnosticDto("CreateProductRequest", "schedules[*][*]", "LocalDate"));

            ExceptionDetailDto unrecognizedDetail =
                    service.detail(store.find(findFingerprint(store, UnrecognizedPropertyException.class.getName())));
            assertThat(unrecognizedDetail.occurrences().get(0).bindingDiagnostics())
                    .containsExactly(new BindingDiagnosticDto(
                            "CreateProductRequest", "(unrecognized property)", "UnrecognizedProperty"));
        }
    }

    private static String findFingerprint(ExceptionStore store, String className) {
        return store.groups().stream()
                .filter(g -> g.exceptionClassName().equals(className))
                .findFirst()
                .orElseThrow()
                .fingerprint();
    }

    static final class CreateProductRequest {}
}
