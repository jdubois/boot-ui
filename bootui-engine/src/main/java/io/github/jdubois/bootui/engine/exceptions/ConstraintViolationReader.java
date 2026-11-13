package io.github.jdubois.bootui.engine.exceptions;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sole {@code bootui-engine} class that imports {@code jakarta.validation}.
 *
 * <p>Only invoked after {@link #hasConstraintViolationInChain(Throwable)} has confirmed by class-name
 * comparison that a throwable in the cause chain is an instance of
 * {@code jakarta.validation.ConstraintViolationException}, so applications without Jakarta Bean
 * Validation on their classpath never load this class.</p>
 */
final class ConstraintViolationReader {

    private static final int MAX_CAUSE_DEPTH = 8;

    private ConstraintViolationReader() {}

    static List<ExceptionStore.BindingDiagnostic> extract(Throwable throwable, String handler) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof ConstraintViolationException cve) {
                List<ExceptionStore.BindingDiagnostic> extracted = extractFromException(cve, handler);
                if (!extracted.isEmpty()) {
                    return extracted;
                }
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return List.of();
    }

    private static List<ExceptionStore.BindingDiagnostic> extractFromException(
            ConstraintViolationException exception, String handler) {
        Set<ConstraintViolation<?>> violations = exception.getConstraintViolations();
        if (violations == null || violations.isEmpty()) {
            return List.of();
        }
        List<ExceptionStore.BindingDiagnostic> diagnostics = new ArrayList<>(violations.size());
        for (ConstraintViolation<?> violation : violations) {
            if (violation == null) {
                continue;
            }
            // Deliberately never reads violation.getInvalidValue() or violation.getExecutableParameters().
            PathAndTarget pathAndTarget = resolvePathAndTarget(violation, handler);
            String reason = resolveReason(violation.getConstraintDescriptor());
            diagnostics.add(new ExceptionStore.BindingDiagnostic(
                    pathAndTarget.targetType(), pathAndTarget.fieldPath(), reason, false));
        }
        diagnostics.sort(Comparator.comparing(ExceptionStore.BindingDiagnostic::fieldPath)
                .thenComparing(ExceptionStore.BindingDiagnostic::reason)
                .thenComparing(ExceptionStore.BindingDiagnostic::targetType));
        return diagnostics;
    }

    private static String resolveReason(ConstraintDescriptor<?> descriptor) {
        if (descriptor != null) {
            Annotation annotation = descriptor.getAnnotation();
            if (annotation != null && annotation.annotationType() != null) {
                String name = annotation.annotationType().getSimpleName();
                if (!name.isEmpty()) {
                    return BindingDiagnostics.truncate(name);
                }
            }
        }
        return "ConstraintViolation";
    }

    private static PathAndTarget resolvePathAndTarget(ConstraintViolation<?> violation, String handler) {
        Path propertyPath = violation.getPropertyPath();
        List<Path.Node> nodes = new ArrayList<>();
        if (propertyPath != null) {
            for (Path.Node node : propertyPath) {
                if (node != null) {
                    nodes.add(node);
                }
            }
        }
        Class<?> rootClass = violation.getRootBeanClass();
        Object leafBean = violation.getLeafBean();

        String methodName = null;
        String paramName = null;
        int startIndex = 0;
        if (!nodes.isEmpty()
                && (nodes.get(0).getKind() == ElementKind.METHOD
                        || nodes.get(0).getKind() == ElementKind.CONSTRUCTOR)) {
            methodName = nodes.get(0).getName();
            startIndex = 1;
            if (nodes.size() > 1 && nodes.get(1).getKind() == ElementKind.PARAMETER) {
                paramName = nodes.get(1).getName();
                if (nodes.size() > 2) {
                    Path.Node firstAfterParam = nodes.get(2);
                    // Keep the parameter name when the parameter itself is an iterable/map container.
                    boolean paramIsContainer = firstAfterParam.isInIterable()
                            && (firstAfterParam.getKind() == ElementKind.CONTAINER_ELEMENT
                                    || firstAfterParam.getKind() == ElementKind.BEAN
                                    || isSyntheticElementName(firstAfterParam.getName()));
                    startIndex = paramIsContainer ? 1 : 2;
                }
            }
        }

        StringBuilder path = new StringBuilder();
        for (int i = startIndex; i < nodes.size(); i++) {
            Path.Node node = nodes.get(i);
            if (node.isInIterable()) {
                if (node.getIndex() != null) {
                    path.append("[*]");
                } else if (node.getKey() != null) {
                    path.append("['")
                            .append(BindingDiagnostics.truncateKey(node.getKey()))
                            .append("']");
                } else {
                    path.append("[*]");
                }
            }
            String name = node.getName();
            if (name == null
                    || name.isBlank()
                    || node.getKind() == ElementKind.BEAN
                    || node.getKind() == ElementKind.CONTAINER_ELEMENT
                    || isSyntheticElementName(name)) {
                continue;
            }
            if (!path.isEmpty()) {
                path.append('.');
            }
            path.append(name);
        }

        String fieldPath = !path.isEmpty()
                ? BindingDiagnostics.normalizeNumericIndices(path.toString())
                : propertyPath != null && !propertyPath.toString().isBlank()
                        ? BindingDiagnostics.normalizeNumericIndices(propertyPath.toString())
                        : paramName != null ? paramName : "(object)";

        String targetType = resolveTargetType(rootClass, leafBean, methodName, paramName, startIndex >= 2, handler);
        return new PathAndTarget(targetType, fieldPath);
    }

    private static String resolveTargetType(
            Class<?> rootClass,
            Object leafBean,
            String methodName,
            String paramName,
            boolean cascadedBeanProperty,
            String handler) {
        if (cascadedBeanProperty) {
            Class<?> paramType = findParameterType(rootClass, methodName, paramName);
            if (paramType != null && !isCollectionOrMap(paramType)) {
                return BindingDiagnostics.simpleTypeName(paramType);
            }
            if (leafBean != null
                    && (rootClass == null || leafBean.getClass() != rootClass)
                    && !isCollectionOrMap(leafBean.getClass())) {
                return BindingDiagnostics.simpleTypeName(leafBean.getClass());
            }
        }
        if (methodName != null) {
            if (handler != null && !handler.isBlank()) {
                return BindingDiagnostics.truncate(handler);
            }
            if (rootClass != null) {
                return BindingDiagnostics.simpleTypeName(rootClass) + "#" + methodName;
            }
        }
        if (rootClass != null) {
            return BindingDiagnostics.simpleTypeName(rootClass);
        }
        if (leafBean != null && !isCollectionOrMap(leafBean.getClass())) {
            return BindingDiagnostics.simpleTypeName(leafBean.getClass());
        }
        return handler != null && !handler.isBlank() ? BindingDiagnostics.truncate(handler) : "Object";
    }

    private static Class<?> findParameterType(Class<?> rootClass, String methodName, String paramName) {
        if (rootClass == null || methodName == null || paramName == null) {
            return null;
        }
        try {
            for (Method method : rootClass.getMethods()) {
                if (!methodName.equals(method.getName())) {
                    continue;
                }
                Parameter[] parameters = method.getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    if (paramName.equals(parameters[i].getName()) || paramName.equals("arg" + i)) {
                        return parameters[i].getType();
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
        return null;
    }

    private static boolean isCollectionOrMap(Class<?> type) {
        return Iterable.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
    }

    private static boolean isSyntheticElementName(String name) {
        return name != null && name.startsWith("<") && name.endsWith(">");
    }

    private record PathAndTarget(String targetType, String fieldPath) {}
}
