package io.github.jdubois.bootui.quarkus.exceptions;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.PropertyBindingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.github.jdubois.bootui.core.dto.BindingDiagnosticDto;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import java.util.List;
import java.util.function.BiFunction;

final class QuarkusBindingDiagnosticsExtractor implements BiFunction<Throwable, String, List<BindingDiagnosticDto>> {

    private static final int MAX_CAUSE_DEPTH = 8;

    @Override
    public List<BindingDiagnosticDto> apply(Throwable throwable, String handler) {
        return extract(throwable, handler);
    }

    List<BindingDiagnosticDto> extract(Throwable throwable, String handler) {
        if (throwable == null) {
            return List.of();
        }
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof MismatchedInputException mismatchedInput) {
                return List.of(fromJacksonMismatchedInput(mismatchedInput, handler));
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return List.of();
    }

    private static BindingDiagnosticDto fromJacksonMismatchedInput(MismatchedInputException exception, String handler) {
        // Never call InvalidFormatException.getValue() or read the parser token stream.
        StringBuilder path = new StringBuilder();
        String rootTargetType = null;
        List<JsonMappingException.Reference> references = exception.getPath();
        if (references != null) {
            for (JsonMappingException.Reference ref : references) {
                if (ref == null) {
                    continue;
                }
                Object from = ref.getFrom();
                if (rootTargetType == null && from != null && !ExceptionStore.isMapReference(from)) {
                    Class<?> fromClass = from instanceof Class<?> c ? c : from.getClass();
                    if (!Iterable.class.isAssignableFrom(fromClass) && !fromClass.isArray()) {
                        rootTargetType = ExceptionStore.simpleTypeName(fromClass);
                    }
                }
                ExceptionStore.appendJsonPathSegment(
                        path, ref.getIndex(), ref.getFieldName(), ExceptionStore.isMapReference(from));
            }
        }
        if (rootTargetType == null) {
            if (exception instanceof PropertyBindingException pbe && pbe.getReferringClass() != null) {
                rootTargetType = ExceptionStore.simpleTypeName(pbe.getReferringClass());
            } else if (exception.getTargetType() != null) {
                rootTargetType = ExceptionStore.simpleTypeName(exception.getTargetType());
            } else {
                rootTargetType = handler != null && !handler.isBlank()
                        ? ExceptionStore.truncateDiagnostic(handler)
                        : "RequestBody";
            }
        }
        if (exception instanceof PropertyBindingException propertyBinding) {
            boolean unrecognized = exception instanceof UnrecognizedPropertyException;
            String fieldPath = !path.isEmpty()
                    ? path.toString()
                    : propertyBinding.getPropertyName() != null ? propertyBinding.getPropertyName() : "(property)";
            String reason = unrecognized ? "UnrecognizedProperty" : "IgnoredProperty";
            return ExceptionStore.diagnostic(rootTargetType, fieldPath, reason, unrecognized);
        }
        String fieldPath = !path.isEmpty() ? path.toString() : "(body)";
        String reason = exception.getTargetType() != null
                ? ExceptionStore.simpleTypeName(exception.getTargetType())
                : "MismatchedInput";
        return new BindingDiagnosticDto(rootTargetType, fieldPath, reason);
    }
}
