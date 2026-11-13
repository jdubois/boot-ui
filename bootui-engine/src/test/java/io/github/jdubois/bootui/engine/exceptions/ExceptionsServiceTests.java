package io.github.jdubois.bootui.engine.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.engine.mcp.McpControlAcks;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ExceptionsServiceTests {

    private static ExposurePolicy policy(ValueExposure exposure, boolean mask) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return mask;
            }
        };
    }

    @Test
    void assemblesGroupedReport() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", "GET", "/x", "Handler#x", "web");

        ExceptionsReport report = new ExceptionsService(policy(ValueExposure.FULL, true)).report(store);

        assertThat(report.available()).isTrue();
        assertThat(report.totalExceptions()).isEqualTo(1);
        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().get(0).message()).isEqualTo("boom");
    }

    @Test
    void clearResetsTheRetainedOccurrenceCount() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", "GET", "/x", "Handler#x", "web");
        store.record(new IllegalStateException("boom"), "main", "GET", "/x", "Handler#x", "web");
        ExceptionsService service = new ExceptionsService(policy(ValueExposure.FULL, true));
        assertThat(service.report(store).totalExceptions()).isEqualTo(2);

        store.clear();

        // totalExceptions counts the occurrences in the retained groups, so it is not a lifetime count.
        ExceptionsReport report = service.report(store);
        assertThat(report.totalExceptions()).isZero();
        assertThat(report.groups()).isEmpty();
        assertThat(McpControlAcks.exceptionsCleared(report))
                .containsEntry("action", "cleared")
                .containsEntry("capturing", true)
                .containsEntry("retained", 0)
                .containsEntry("capacity", 100)
                .containsEntry("totalCaptured", null);
    }

    @Test
    void surfacesLastTraceIdFromOccurrence() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "worker-1", "GET", "/x", "Handler#x", "web", "trace-a");

        ExceptionsReport report = new ExceptionsService(policy(ValueExposure.FULL, true)).report(store);

        assertThat(report.groups().get(0).lastTraceId()).isEqualTo("trace-a");
    }

    @Test
    void lastTraceIdIsNullWhenNoneRecorded() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", "GET", "/x", "Handler#x", "web");

        ExceptionsReport report = new ExceptionsService(policy(ValueExposure.FULL, true)).report(store);

        assertThat(report.groups().get(0).lastTraceId()).isNull();
    }

    @Test
    void masksSecretAssignmentsInMaskedMode() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("auth failed password=hunter2"), "main", null, null, null, "log");

        ExceptionGroupDto group = new ExceptionsService(policy(ValueExposure.MASKED, true))
                .report(store)
                .groups()
                .get(0);

        assertThat(group.message())
                .contains("password=")
                .doesNotContain("hunter2")
                .contains("******");
    }

    @Test
    void masksTheCredentialAfterAnAuthorizationSchemeInGroupAndCauseMessages() {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln";
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(
                new IllegalStateException(
                        "upstream call failed\nAuthorization: Bearer " + token,
                        new IllegalArgumentException("retried with Bearer " + token)),
                "main",
                null,
                null,
                null,
                "log");
        ExceptionsService service = new ExceptionsService(policy(ValueExposure.MASKED, true));

        ExceptionGroupDto group = service.report(store).groups().get(0);
        ExceptionStore.GroupDetail detail = store.find(group.id());

        assertThat(group.message()).isEqualTo("upstream call failed\nAuthorization: Bearer ******");
        assertThat(service.detail(detail).causes().get(0).message()).isEqualTo("retried with Bearer ******");
    }

    @Test
    void omitsMessageInMetadataOnlyMode() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");

        assertThat(new ExceptionsService(policy(ValueExposure.METADATA_ONLY, true))
                        .report(store)
                        .groups()
                        .get(0)
                        .message())
                .isNull();
    }

    @Test
    void messagesMatchThePreSharedRuleInEveryExposureMode() {
        // The secret-assignment pattern moved into the shared MessageExposure helper. This pins the exact output
        // the service produced before the move, for group and cause messages alike.
        Pattern before = Pattern.compile(
                "(?i)([\"']?(?:password|passwd|pwd|secret|token|api[-_]?key|apikey|authorization|credential|"
                        + "access[-_]?key|client[-_]?secret|private[-_]?key)[\"']?\\s*[=:]\\s*[\"']?)([^\\s\"',;&)]+)");
        List<String> messages = List.of(
                "auth failed password=hunter2",
                "{\"apiKey\": \"ak-1\", 'client_secret':'cs-2'}",
                "multi\nline token: tok-3\nAuthorization: basic-4",
                "Access-Key = ak5, private_key=pk6; pwd=(x)",
                "nothing secret here",
                "");
        for (ValueExposure mode : ValueExposure.values()) {
            for (boolean mask : new boolean[] {true, false}) {
                for (String message : messages) {
                    ExceptionsService service = new ExceptionsService(policy(mode, mask));
                    String expected = mode == ValueExposure.METADATA_ONLY
                            ? null
                            : mode == ValueExposure.MASKED && mask
                                    ? before.matcher(message).replaceAll(result -> result.group(1) + "******")
                                    : message;

                    ExceptionStore direct = new ExceptionStore(100, 25, 50);
                    direct.record(new IllegalStateException(message), "main", null, null, null, "log");
                    ExceptionStore wrapped = new ExceptionStore(100, 25, 50);
                    wrapped.record(
                            new IllegalStateException("outer", new IllegalArgumentException(message)),
                            "main",
                            null,
                            null,
                            null,
                            "log");
                    ExceptionStore.GroupDetail detail =
                            wrapped.find(wrapped.groups().get(0).fingerprint());

                    assertThat(service.report(direct).groups().get(0).message())
                            .as("%s/%s group: %s", mode, mask, message)
                            .isEqualTo(expected);
                    assertThat(service.detail(detail).causes().get(0).message())
                            .as("%s/%s cause: %s", mode, mask, message)
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void reportSurfacesStatusAndRegressionCount() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");

        ExceptionGroupDto group = new ExceptionsService(policy(ValueExposure.FULL, true))
                .report(store)
                .groups()
                .get(0);

        assertThat(group.status()).isEqualTo("OPEN");
        assertThat(group.regressionCount()).isZero();
    }

    @Test
    void parseStatusAcceptsValidValuesCaseInsensitively() {
        assertThat(ExceptionsService.parseStatus("open")).isEqualTo(ExceptionStore.Status.OPEN);
        assertThat(ExceptionsService.parseStatus("Acknowledged")).isEqualTo(ExceptionStore.Status.ACKNOWLEDGED);
        assertThat(ExceptionsService.parseStatus("RESOLVED")).isEqualTo(ExceptionStore.Status.RESOLVED);
    }

    @Test
    void parseStatusRejectsBlankOrUnknownValues() {
        assertThatThrownBy(() -> ExceptionsService.parseStatus(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExceptionsService.parseStatus("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExceptionsService.parseStatus("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExceptionsService.parseStatus("CLOSED")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateStatusReturnsTheMappedDtoOnSuccess() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");
        String fingerprint = store.groups().get(0).fingerprint();

        ExceptionGroupDto updated =
                new ExceptionsService(policy(ValueExposure.FULL, true)).updateStatus(store, fingerprint, "resolved");

        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo("RESOLVED");
        assertThat(updated.regressionCount()).isZero();
    }

    @Test
    void updateStatusReturnsNullForAnUnknownStoreOrFingerprint() {
        ExceptionsService service = new ExceptionsService(policy(ValueExposure.FULL, true));

        assertThat(service.updateStatus(null, "abc", "OPEN")).isNull();

        ExceptionStore store = new ExceptionStore(100, 25, 50);
        assertThat(service.updateStatus(store, "does-not-exist", "OPEN")).isNull();
    }

    @Test
    void updateStatusValidatesBeforeCheckingExistenceSoAnInvalidStatusAlwaysYieldsAnException() {
        ExceptionsService service = new ExceptionsService(policy(ValueExposure.FULL, true));

        assertThatThrownBy(() -> service.updateStatus(null, "unknown-fingerprint", "CLOSED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractsPerOccurrenceBindingDiagnosticsAndRedactsMapKeysAndUnrecognizedPropertiesExceptInFull() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        List<Throwable> failures = List.of(
                new IllegalArgumentException(
                        "Field error in object 'createOrderRequest' on field 'attributes[secretMapKey]': "
                                + "rejected value [raw-secret-1]; codes [NotBlank]"),
                new IllegalArgumentException(
                        "Unrecognized field \"secretUnknownProp\" (class com.example.CreateOrderRequest), "
                                + "not marked as ignorable (through reference chain: "
                                + "com.example.CreateOrderRequest[\"secretUnknownProp\"])"));

        store.record(
                failures.get(0),
                "http-1",
                "POST",
                "/api/orders",
                "OrderController#create",
                "web",
                null,
                List.of(
                        new ExceptionStore.BindingDiagnostic(
                                "CreateOrderRequest", "items[0].quantity", "Positive", false),
                        new ExceptionStore.BindingDiagnostic(
                                "CreateOrderRequest", "attributes['secretMapKey']", "NotBlank", false)));
        store.record(
                failures.get(1),
                "http-2",
                "POST",
                "/api/orders",
                "OrderController#create",
                "web",
                null,
                List.of(new ExceptionStore.BindingDiagnostic(
                        "CreateOrderRequest", "secretUnknownProp", "UnrecognizedProperty", true)));

        String id = store.groups().get(0).fingerprint();
        ExceptionStore.GroupDetail rawDetail = store.find(id);

        for (ValueExposure mode : List.of(ValueExposure.MASKED, ValueExposure.METADATA_ONLY)) {
            ExceptionsService service = new ExceptionsService(policy(mode, true));
            var report = service.report(store);
            var detail = service.detail(rawDetail);

            assertThat(report.toString())
                    .as("no leak in report under %s", mode)
                    .doesNotContain("raw-secret-1", "secretMapKey", "secretUnknownProp");
            assertThat(detail.toString())
                    .as("no leak in detail under %s", mode)
                    .doesNotContain("raw-secret-1", "secretMapKey", "secretUnknownProp");

            // Latest occurrence is index 0 (the unrecognized property failure).
            assertThat(detail.occurrences().get(0).bindingDiagnostics())
                    .containsExactly(new io.github.jdubois.bootui.core.dto.BindingDiagnosticDto(
                            "CreateOrderRequest", "(unrecognized property)", "UnrecognizedProperty"));
            // Earlier occurrence is index 1 (the map-key + list-index failure).
            assertThat(detail.occurrences().get(1).bindingDiagnostics())
                    .containsExactly(
                            new io.github.jdubois.bootui.core.dto.BindingDiagnosticDto(
                                    "CreateOrderRequest", "items[*].quantity", "Positive"),
                            new io.github.jdubois.bootui.core.dto.BindingDiagnosticDto(
                                    "CreateOrderRequest", "attributes[*]", "NotBlank"));
        }

        // Under FULL, map keys and unrecognized property names are shown, while numeric list indices stay [*].
        var fullDetail = new ExceptionsService(policy(ValueExposure.FULL, true)).detail(rawDetail);
        assertThat(fullDetail.occurrences().get(0).bindingDiagnostics())
                .containsExactly(new io.github.jdubois.bootui.core.dto.BindingDiagnosticDto(
                        "CreateOrderRequest", "secretUnknownProp", "UnrecognizedProperty"));
        assertThat(fullDetail.occurrences().get(1).bindingDiagnostics())
                .containsExactly(
                        new io.github.jdubois.bootui.core.dto.BindingDiagnosticDto(
                                "CreateOrderRequest", "items[*].quantity", "Positive"),
                        new io.github.jdubois.bootui.core.dto.BindingDiagnosticDto(
                                "CreateOrderRequest", "attributes['secretMapKey']", "NotBlank"));
    }

    @Test
    void capsBindingDiagnosticsPerOccurrenceAtTenAndMarksTruncation() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        List<ExceptionStore.BindingDiagnostic> twelve = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> new ExceptionStore.BindingDiagnostic("CreateOrderRequest", "field" + i, "NotBlank"))
                .toList();

        store.record(
                new IllegalArgumentException("validation failed"),
                "http-1",
                "POST",
                "/orders",
                "H#c",
                "web",
                null,
                twelve);

        var detail = new ExceptionsService(policy(ValueExposure.MASKED, true))
                .detail(store.find(store.groups().get(0).fingerprint()));
        var occurrence = detail.occurrences().get(0);
        assertThat(occurrence.bindingDiagnostics()).hasSize(10);
        assertThat(occurrence.bindingDiagnosticsTruncated()).isTrue();
    }

    @Test
    void extractsJakartaConstraintViolationsWithoutReadingInvalidValues() {
        jakarta.validation.Path path = org.mockito.Mockito.mock(jakarta.validation.Path.class);
        jakarta.validation.Path.Node mapNode = org.mockito.Mockito.mock(jakarta.validation.Path.Node.class);
        org.mockito.Mockito.when(mapNode.getName()).thenReturn("attributes");
        org.mockito.Mockito.when(mapNode.getKind()).thenReturn(jakarta.validation.ElementKind.PROPERTY);
        jakarta.validation.Path.Node valueNode = org.mockito.Mockito.mock(jakarta.validation.Path.Node.class);
        org.mockito.Mockito.when(valueNode.getName()).thenReturn("<map value>");
        org.mockito.Mockito.when(valueNode.isInIterable()).thenReturn(true);
        org.mockito.Mockito.when(valueNode.getKey()).thenReturn("secretKey");
        org.mockito.Mockito.when(valueNode.getKind()).thenReturn(jakarta.validation.ElementKind.CONTAINER_ELEMENT);
        org.mockito.Mockito.when(path.iterator())
                .thenAnswer(inv -> List.of(mapNode, valueNode).iterator());

        @SuppressWarnings("unchecked")
        jakarta.validation.metadata.ConstraintDescriptor<jakarta.validation.constraints.NotBlank> descriptor =
                org.mockito.Mockito.mock(jakarta.validation.metadata.ConstraintDescriptor.class);
        jakarta.validation.constraints.NotBlank annotation =
                org.mockito.Mockito.mock(jakarta.validation.constraints.NotBlank.class);
        org.mockito.Mockito.doReturn(jakarta.validation.constraints.NotBlank.class)
                .when(annotation)
                .annotationType();
        org.mockito.Mockito.when(descriptor.getAnnotation()).thenReturn(annotation);

        @SuppressWarnings("unchecked")
        jakarta.validation.ConstraintViolation<Object> violation =
                org.mockito.Mockito.mock(jakarta.validation.ConstraintViolation.class);
        org.mockito.Mockito.when(violation.getPropertyPath()).thenReturn(path);
        org.mockito.Mockito.doReturn(SampleOrderBean.class).when(violation).getRootBeanClass();
        org.mockito.Mockito.doReturn(descriptor).when(violation).getConstraintDescriptor();
        org.mockito.Mockito.when(violation.getInvalidValue())
                .thenThrow(new AssertionError("getInvalidValue() must never be called"));
        org.mockito.Mockito.when(violation.getExecutableParameters())
                .thenThrow(new AssertionError("getExecutableParameters() must never be called"));

        jakarta.validation.ConstraintViolationException cve = new jakarta.validation.ConstraintViolationException(
                "attributes[secretKey]: must not be blank (rejected value [super-secret-value])",
                java.util.Set.of(violation));

        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(cve, "http-1", "POST", "/api/orders", "OrderController#create", "web");

        ExceptionsService maskedService = new ExceptionsService(policy(ValueExposure.MASKED, true));
        var group = maskedService.report(store).groups().get(0);
        var detail = maskedService.detail(store.find(group.id()));

        assertThat(group.message())
                .doesNotContain("secretKey", "super-secret-value")
                .contains("attributes[*]: must not be blank", "rejected value [******]");
        assertThat(detail.occurrences().get(0).bindingDiagnostics())
                .containsExactly(new io.github.jdubois.bootui.core.dto.BindingDiagnosticDto(
                        "SampleOrderBean", "attributes[*]", "NotBlank"));
    }

    static final class SampleOrderBean {}
}
