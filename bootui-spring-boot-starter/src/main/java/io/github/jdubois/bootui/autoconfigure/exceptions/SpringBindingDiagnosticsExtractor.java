package io.github.jdubois.bootui.autoconfigure.exceptions;

import io.github.jdubois.bootui.core.dto.BindingDiagnosticDto;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ServerWebInputException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.PropertyBindingException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

final class SpringBindingDiagnosticsExtractor implements BiFunction<Throwable, String, List<BindingDiagnosticDto>> {

    private static final int MAX_CAUSE_DEPTH = 8;

    @Override
    public List<BindingDiagnosticDto> apply(Throwable throwable, String handler) {
        return extract(throwable, null, handler);
    }

    List<BindingDiagnosticDto> extract(Throwable throwable, String handler) {
        return extract(throwable, null, handler);
    }

    List<BindingDiagnosticDto> extract(Throwable throwable, Object rawHandler, String handlerDescription) {
        if (throwable == null) {
            return List.of();
        }
        Throwable current = throwable;
        int depth = 0;
        MethodParameter enclosingParameter = null;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof MethodArgumentNotValidException manve) {
                enclosingParameter = manve.getParameter();
            } else if (current instanceof ServerWebInputException swie && swie.getMethodParameter() != null) {
                enclosingParameter = swie.getMethodParameter();
            }
            if (current instanceof BindingResult bindingResult && bindingResult.hasErrors()) {
                return fromBindingResult(bindingResult, enclosingParameter, handlerDescription);
            }
            if (current instanceof MethodValidationResult validationResult) {
                List<BindingDiagnosticDto> diagnostics = fromMethodValidation(validationResult, handlerDescription);
                if (!diagnostics.isEmpty()) {
                    return diagnostics;
                }
            }
            if (current instanceof MethodArgumentTypeMismatchException mismatch) {
                return List.of(fromMethodArgumentTypeMismatch(mismatch, handlerDescription));
            }
            if (current instanceof MismatchedInputException mismatchedInput) {
                return List.of(fromJacksonMismatchedInput(
                        mismatchedInput, enclosingParameter, rawHandler, handlerDescription));
            }
            if (current instanceof TypeMismatchException typeMismatch) {
                return List.of(fromTypeMismatch(typeMismatch, enclosingParameter, handlerDescription));
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return List.of();
    }

    private static List<BindingDiagnosticDto> fromBindingResult(
            BindingResult bindingResult, MethodParameter parameter, String handler) {
        String targetType = resolveBindingTargetType(bindingResult, parameter, handler);
        List<BindingDiagnosticDto> diagnostics = new ArrayList<>();
        for (FieldError fieldError : bindingResult.getFieldErrors()) {
            if (fieldError == null) {
                continue;
            }
            // Never call fieldError.getRejectedValue().
            String fieldPath = fieldError.getField();
            String reason = resolveErrorCode(fieldError, fieldError.isBindingFailure() ? "typeMismatch" : "Invalid");
            diagnostics.add(new BindingDiagnosticDto(targetType, fieldPath, reason));
        }
        for (ObjectError globalError : bindingResult.getGlobalErrors()) {
            if (globalError == null) {
                continue;
            }
            String reason = resolveErrorCode(globalError, "ObjectValidation");
            diagnostics.add(new BindingDiagnosticDto(targetType, "(object)", reason));
        }
        diagnostics.sort(Comparator.comparing(BindingDiagnosticDto::fieldPath)
                .thenComparing(BindingDiagnosticDto::reason)
                .thenComparing(BindingDiagnosticDto::targetType));
        return diagnostics;
    }

    private static List<BindingDiagnosticDto> fromMethodValidation(
            MethodValidationResult validationResult, String handler) {
        List<BindingDiagnosticDto> diagnostics = new ArrayList<>();
        for (ParameterValidationResult paramResult : validationResult.getParameterValidationResults()) {
            if (paramResult == null) {
                continue;
            }
            if (paramResult instanceof ParameterErrors parameterErrors && parameterErrors.hasErrors()) {
                diagnostics.addAll(fromParameterErrors(parameterErrors, handler));
                continue;
            }
            // Never call paramResult.getArgument().
            MethodParameter parameter = paramResult.getMethodParameter();
            String targetType = resolveMethodTarget(validationResult, parameter, handler);
            String basePath = resolveParameterName(parameter);
            StringBuilder path = new StringBuilder(basePath);
            if (paramResult.getContainerIndex() != null) {
                path.append("[*]");
            } else if (paramResult.getContainerKey() != null) {
                path.append("['")
                        .append(ExceptionStore.truncateDiagnosticKey(paramResult.getContainerKey()))
                        .append("']");
            }
            for (MessageSourceResolvable error : paramResult.getResolvableErrors()) {
                if (error == null) {
                    continue;
                }
                String reason = resolveErrorCode(error, "ParameterValidation");
                diagnostics.add(new BindingDiagnosticDto(targetType, path.toString(), reason));
            }
        }
        for (MessageSourceResolvable error : validationResult.getCrossParameterValidationResults()) {
            if (error == null) {
                continue;
            }
            String targetType = resolveMethodTarget(validationResult, null, handler);
            String reason = resolveErrorCode(error, "CrossParameterValidation");
            diagnostics.add(new BindingDiagnosticDto(targetType, "(parameters)", reason));
        }
        diagnostics.sort(Comparator.comparing(BindingDiagnosticDto::fieldPath)
                .thenComparing(BindingDiagnosticDto::reason)
                .thenComparing(BindingDiagnosticDto::targetType));
        return diagnostics;
    }

    private static List<BindingDiagnosticDto> fromParameterErrors(ParameterErrors parameterErrors, String handler) {
        String targetType = resolveParameterBeanTarget(parameterErrors, handler);
        List<BindingDiagnosticDto> diagnostics = new ArrayList<>();
        String containerPrefix = "";
        if (parameterErrors.getContainerIndex() != null) {
            containerPrefix = "[*].";
        } else if (parameterErrors.getContainerKey() != null) {
            containerPrefix = "['" + ExceptionStore.truncateDiagnosticKey(parameterErrors.getContainerKey()) + "'].";
        }
        for (FieldError fieldError : parameterErrors.getFieldErrors()) {
            if (fieldError == null) {
                continue;
            }
            String fieldPath = containerPrefix + fieldError.getField();
            String reason = resolveErrorCode(fieldError, fieldError.isBindingFailure() ? "typeMismatch" : "Invalid");
            diagnostics.add(new BindingDiagnosticDto(targetType, fieldPath, reason));
        }
        for (ObjectError globalError : parameterErrors.getGlobalErrors()) {
            if (globalError == null) {
                continue;
            }
            String reason = resolveErrorCode(globalError, "ObjectValidation");
            diagnostics.add(new BindingDiagnosticDto(targetType, "(object)", reason));
        }
        return diagnostics;
    }

    private static BindingDiagnosticDto fromMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException mismatch, String handler) {
        // Never call mismatch.getValue().
        String targetType = resolveMethodTarget(null, mismatch.getParameter(), handler);
        String fieldPath = mismatch.getName() != null && !mismatch.getName().isBlank()
                ? mismatch.getName()
                : mismatch.getPropertyName() != null
                        ? mismatch.getPropertyName()
                        : resolveParameterName(mismatch.getParameter());
        String reason = mismatch.getRequiredType() != null
                ? ExceptionStore.simpleTypeName(mismatch.getRequiredType())
                : "typeMismatch";
        return new BindingDiagnosticDto(targetType, fieldPath, reason);
    }

    private static BindingDiagnosticDto fromTypeMismatch(
            TypeMismatchException mismatch, MethodParameter parameter, String handler) {
        // Never call mismatch.getValue().
        String targetType = resolveMethodTarget(null, parameter, handler);
        String fieldPath = mismatch.getPropertyName() != null
                        && !mismatch.getPropertyName().isBlank()
                ? mismatch.getPropertyName()
                : resolveParameterName(parameter);
        String reason = mismatch.getRequiredType() != null
                ? ExceptionStore.simpleTypeName(mismatch.getRequiredType())
                : "typeMismatch";
        return new BindingDiagnosticDto(targetType, fieldPath, reason);
    }

    private static BindingDiagnosticDto fromJacksonMismatchedInput(
            MismatchedInputException exception,
            MethodParameter parameter,
            Object rawHandler,
            String handlerDescription) {
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
            rootTargetType = resolvePayloadTargetType(exception, parameter, rawHandler, handlerDescription);
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

    private static String resolvePayloadTargetType(
            MismatchedInputException exception,
            MethodParameter parameter,
            Object rawHandler,
            String handlerDescription) {
        if (exception instanceof PropertyBindingException pbe && pbe.getReferringClass() != null) {
            return ExceptionStore.simpleTypeName(pbe.getReferringClass());
        }
        if (parameter != null && parameter.getParameterType() != Object.class) {
            return ExceptionStore.simpleTypeName(parameter.getParameterType());
        }
        if (rawHandler instanceof HandlerMethod handlerMethod) {
            for (MethodParameter mp : handlerMethod.getMethodParameters()) {
                if (mp.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class)) {
                    return ExceptionStore.simpleTypeName(mp.getParameterType());
                }
            }
        }
        if (exception.getTargetType() != null) {
            return ExceptionStore.simpleTypeName(exception.getTargetType());
        }
        return handlerDescription != null && !handlerDescription.isBlank()
                ? ExceptionStore.truncateDiagnostic(handlerDescription)
                : "RequestBody";
    }

    private static String resolveBindingTargetType(
            BindingResult bindingResult, MethodParameter parameter, String handler) {
        Object target = bindingResult.getTarget();
        if (target != null && !(target instanceof java.util.Map<?, ?>) && !(target instanceof Iterable<?>)) {
            return ExceptionStore.simpleTypeName(target.getClass());
        }
        String objectName = bindingResult.getObjectName();
        if (objectName != null && !objectName.isBlank() && !"target".equals(objectName)) {
            return Character.toUpperCase(objectName.charAt(0)) + objectName.substring(1);
        }
        if (parameter != null
                && parameter.getParameterType() != Object.class
                && parameter.getParameterType() != String.class) {
            return ExceptionStore.simpleTypeName(parameter.getParameterType());
        }
        return handler != null && !handler.isBlank() ? ExceptionStore.truncateDiagnostic(handler) : "Object";
    }

    private static String resolveParameterBeanTarget(ParameterErrors parameterErrors, String handler) {
        MethodParameter parameter = parameterErrors.getMethodParameter();
        if (parameter != null
                && parameter.getParameterType() != Object.class
                && !Iterable.class.isAssignableFrom(parameter.getParameterType())
                && !java.util.Map.class.isAssignableFrom(parameter.getParameterType())) {
            return ExceptionStore.simpleTypeName(parameter.getParameterType());
        }
        String objectName = parameterErrors.getObjectName();
        if (objectName != null && !objectName.isBlank()) {
            return Character.toUpperCase(objectName.charAt(0)) + objectName.substring(1);
        }
        return resolveMethodTarget(null, parameter, handler);
    }

    private static String resolveMethodTarget(
            MethodValidationResult validationResult, MethodParameter parameter, String handler) {
        if (handler != null && !handler.isBlank()) {
            return ExceptionStore.truncateDiagnostic(handler);
        }
        if (parameter != null) {
            Method method = parameter.getMethod();
            Class<?> containingClass = parameter.getContainingClass();
            if (containingClass != null && method != null) {
                return ExceptionStore.simpleTypeName(containingClass) + "#" + method.getName();
            }
            if (containingClass != null) {
                return ExceptionStore.simpleTypeName(containingClass);
            }
        }
        if (validationResult != null) {
            Method method = validationResult.getMethod();
            Object target = validationResult.getTarget();
            if (target != null && method != null) {
                return ExceptionStore.simpleTypeName(target.getClass()) + "#" + method.getName();
            }
            if (method != null) {
                return ExceptionStore.simpleTypeName(method.getDeclaringClass()) + "#" + method.getName();
            }
        }
        return "HandlerMethod";
    }

    private static String resolveParameterName(MethodParameter parameter) {
        if (parameter == null) {
            return "(parameter)";
        }
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            String name = !requestParam.name().isBlank() ? requestParam.name() : requestParam.value();
            if (!name.isBlank()) {
                return name;
            }
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            String name = !pathVariable.name().isBlank() ? pathVariable.name() : pathVariable.value();
            if (!name.isBlank()) {
                return name;
            }
        }
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null) {
            String name = !requestHeader.name().isBlank() ? requestHeader.name() : requestHeader.value();
            if (!name.isBlank()) {
                return name;
            }
        }
        String paramName = parameter.getParameterName();
        if (paramName != null && !paramName.isBlank()) {
            return paramName;
        }
        Method method = parameter.getMethod();
        int index = parameter.getParameterIndex();
        if (method != null && index >= 0 && index < method.getParameterCount()) {
            java.lang.reflect.Parameter reflected = method.getParameters()[index];
            if (reflected.isNamePresent()) {
                return reflected.getName();
            }
        }
        return index >= 0 ? "arg" + index : "(parameter)";
    }

    private static String resolveErrorCode(MessageSourceResolvable error, String fallback) {
        String[] codes = error.getCodes();
        if (codes != null && codes.length > 0) {
            String last = codes[codes.length - 1];
            if (last != null && !last.isBlank()) {
                int dot = last.indexOf('.');
                return ExceptionStore.truncateDiagnostic(dot > 0 ? last.substring(0, dot) : last);
            }
        }
        return fallback;
    }
}
