package io.github.jdubois.bootui.autoconfigure.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ServerWebInputException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

class SpringBindingDiagnosticsExtractorTests {

    private final SpringBindingDiagnosticsExtractor extractor = new SpringBindingDiagnosticsExtractor();

    @Test
    void extractsMethodArgumentNotValidAndBindExceptionWithoutCallingGetRejectedValue() throws Exception {
        CreateOrderRequest target = new CreateOrderRequest();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "createOrderRequest");
        bindingResult.addError(new ForbiddingFieldError("createOrderRequest", "items[2].quantity", -5, new String[] {
            "Positive.createOrderRequest.items.quantity", "Positive"
        }));
        bindingResult.addError(
                new ForbiddingFieldError("createOrderRequest", "attributes['secretKey']", "raw-secret", new String[] {
                    "NotBlank.createOrderRequest.attributes", "NotBlank"
                }));
        bindingResult.addError(
                new ObjectError("createOrderRequest", new String[] {"ValidOrderTotal"}, null, "invalid total"));

        MethodParameter parameter =
                new MethodParameter(OrderController.class.getDeclaredMethod("create", CreateOrderRequest.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        List<BindingDiagnosticDto> diagnostics = extractor.extract(ex, "OrderController#create");

        assertThat(diagnostics)
                .containsExactly(
                        new BindingDiagnosticDto("CreateOrderRequest", "(object)", "ValidOrderTotal"),
                        new BindingDiagnosticDto("CreateOrderRequest", "attributes['secretKey']", "NotBlank"),
                        new BindingDiagnosticDto("CreateOrderRequest", "items[2].quantity", "Positive"));

        BindException bindEx = new BindException(bindingResult);
        assertThat(extractor.extract(bindEx, "OrderController#create")).isEqualTo(diagnostics);

        WebExchangeBindException webExchangeEx = new WebExchangeBindException(parameter, bindingResult);
        assertThat(extractor.extract(webExchangeEx, "OrderController#create")).isEqualTo(diagnostics);
    }

    @Test
    void extractsHandlerMethodValidationExceptionForParametersAndBeanErrorsWithoutReadingArgument() throws Exception {
        MethodParameter pageParam = new MethodParameter(
                OrderController.class.getDeclaredMethod("list", int.class, CreateOrderRequest.class), 0);
        MethodParameter bodyParam = new MethodParameter(
                OrderController.class.getDeclaredMethod("list", int.class, CreateOrderRequest.class), 1);

        ParameterValidationResult directParamResult =
                new ParameterValidationResult(
                        pageParam,
                        -1,
                        List.of(new DefaultMessageSourceResolvable(
                                new String[] {"Min.page", "Min"}, null, "must be >= 1")),
                        null,
                        null,
                        null,
                        (error, type) -> null) {
                    @Override
                    public Object getArgument() {
                        throw new AssertionError("getArgument() must never be called");
                    }
                };

        BeanPropertyBindingResult beanErrors =
                new BeanPropertyBindingResult(new CreateOrderRequest(), "createOrderRequest");
        beanErrors.addError(
                new ForbiddingFieldError("createOrderRequest", "customer.email", "bad-email", new String[] {"Email"}));
        ParameterErrors parameterErrors = new ParameterErrors(bodyParam, null, beanErrors, null, null, "secretMapKey");

        MethodValidationResult result = MethodValidationResult.create(
                new OrderController(),
                OrderController.class.getDeclaredMethod("list", int.class, CreateOrderRequest.class),
                List.of(directParamResult, parameterErrors));
        HandlerMethodValidationException ex = new HandlerMethodValidationException(result);

        List<BindingDiagnosticDto> diagnostics = extractor.extract(ex, "OrderController#list");

        assertThat(diagnostics)
                .containsExactly(
                        new BindingDiagnosticDto("CreateOrderRequest", "['secretMapKey'].customer.email", "Email"),
                        new BindingDiagnosticDto("OrderController#list", "page", "Min"));
    }

    @Test
    void extractsMethodArgumentTypeMismatchAndServerWebInputTypeMismatchWithoutReadingValue() throws Exception {
        MethodParameter pageParam = new MethodParameter(
                OrderController.class.getDeclaredMethod("list", int.class, CreateOrderRequest.class), 0);
        MethodArgumentTypeMismatchException mismatch =
                new MethodArgumentTypeMismatchException(
                        "secret-not-an-int",
                        int.class,
                        "page",
                        pageParam,
                        new NumberFormatException("For input string")) {
                    @Override
                    public Object getValue() {
                        throw new AssertionError("getValue() must never be called");
                    }
                };

        assertThat(extractor.extract(mismatch, "OrderController#list"))
                .containsExactly(new BindingDiagnosticDto("OrderController#list", "page", "Integer"));

        ServerWebInputException webInput = new ServerWebInputException("Type mismatch", pageParam, mismatch);
        assertThat(extractor.extract(webInput, "OrderController#list"))
                .containsExactly(new BindingDiagnosticDto("OrderController#list", "page", "Integer"));
    }

    @Test
    void extractsJackson3InvalidFormatAndUnrecognizedPropertyAndNeverLeaksValuesInMaskedOrMetadataOnly() {
        InvalidFormatException invalidFormat = InvalidFormatException.from(
                null,
                "Cannot deserialize value of type `java.time.LocalDate` from String \"2026-99-99-secret\": Failed",
                "2026-99-99-secret",
                LocalDate.class);
        invalidFormat.prependPath(Map.class, "secretMapKey");
        invalidFormat.prependPath(CreateOrderRequest.class, "deliveryDates");
        tools.jackson.databind.ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        UnrecognizedPropertyException unrecognized = org.assertj.core.api.Assertions.catchThrowableOfType(
                UnrecognizedPropertyException.class,
                () -> mapper.readValue("{\"secretAdminFlag\":\"top-secret\"}", CreateOrderRequest.class));

        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.setBindingDiagnosticExtractor(extractor);
        for (Throwable cause : List.of(invalidFormat, unrecognized)) {
            HttpMessageNotReadableException notReadable = new HttpMessageNotReadableException(
                    "JSON parse error: " + cause.getMessage(), cause, new MockHttpInputMessage(new byte[0]));
            store.record(notReadable, "http-1", "POST", "/api/orders", "OrderController#create", "web");
        }

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
            ExceptionDetailDto detail =
                    service.detail(store.find(store.groups().get(0).fingerprint()));
            assertThat(detail.toString())
                    .as("zero rejected-value or unknown-property leakage under %s", mode)
                    .doesNotContain("2026-99-99-secret", "secretMapKey", "secretAdminFlag");
            assertThat(detail.occurrences().get(0).bindingDiagnostics())
                    .containsExactly(new BindingDiagnosticDto(
                            "CreateOrderRequest", "(unrecognized property)", "UnrecognizedProperty"));
            assertThat(detail.occurrences().get(1).bindingDiagnostics())
                    .containsExactly(new BindingDiagnosticDto("CreateOrderRequest", "deliveryDates[*]", "LocalDate"));
        }
    }

    static final class CreateOrderRequest {}

    private static HttpMessageNotReadableException wrapNotReadable(Throwable cause) {
        return new HttpMessageNotReadableException(
                "JSON parse error: " + cause.getMessage(), cause, new MockHttpInputMessage(new byte[0]));
    }

    static final class OrderController {
        public void create(@RequestBody CreateOrderRequest request) {}

        public void list(@RequestParam("page") int page, @RequestBody CreateOrderRequest request) {}
    }

    private static final class ForbiddingFieldError extends FieldError {
        ForbiddingFieldError(String objectName, String field, Object rejectedValue, String[] codes) {
            super(objectName, field, rejectedValue, false, codes, null, "invalid");
        }

        @Override
        public Object getRejectedValue() {
            throw new AssertionError("getRejectedValue() must never be called by BootUI");
        }
    }
}
