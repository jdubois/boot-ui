package io.github.jdubois.bootui.quarkus4.exceptions;

import io.github.jdubois.bootui.core.dto.BindingDiagnosticDto;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import java.util.List;
import java.util.function.BiFunction;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.PropertyBindingException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

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
        List<JacksonException.Reference> references = exception.getPath();
        if (references != null) {
            for (JacksonException.Reference ref : references) {
                if (ref == null) {
                    continue;
                }
                Object from = ref.from();
                if (rootTargetType == null && from != null && !ExceptionStore.isMapReference(from)) {
                    Class<?> fromClass = from instanceof Class<?> c ? c : from.getClass();
                    if (!Iterable.class.isAssignableFrom(fromClass) && !fromClass.isArray()) {
                        rootTargetType = ExceptionStore.simpleTypeName(fromClass);
                    }
                }
                ExceptionStore.appendJsonPathSegment(
                        path, ref.getIndex(), ref.getPropertyName(), ExceptionStore.isMapReference(from));
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
