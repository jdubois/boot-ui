package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact bytecode shape of the OpenAPI Generator Spring (servlet) {@code ApiUtil} template, for Java
 * ({@code JavaSpring/apiUtil.mustache}) and Kotlin ({@code kotlin-spring/apiUtil.mustache}).
 *
 * <p>The template carries no bytecode-retained marker, so packaged applications without a source tree
 * cannot prove provenance. Every member, call, and handler must match; any handwritten addition fails the
 * fingerprint. Types are compared by name only, so neither Spring nor a servlet API is loaded.</p>
 */
final class OpenApiGeneratorApiUtilFingerprint {
    private static final String NAME = "ApiUtil";
    private static final String METHOD = "setExampleResponse";
    private static final String NATIVE_WEB_REQUEST = "org.springframework.web.context.request.NativeWebRequest";
    private static final List<String> PARAMETERS = List.of(NATIVE_WEB_REQUEST, "java.lang.String", "java.lang.String");
    private static final String GET_NATIVE_RESPONSE = NATIVE_WEB_REQUEST + "#getNativeResponse";
    private static final String PRINT = "java.io.PrintWriter#print";
    private static final Set<String> SERVLET_RESPONSES =
            Set.of("jakarta.servlet.http.HttpServletResponse", "javax.servlet.http.HttpServletResponse");
    private static final Set<String> SERVLET_RESPONSE_METHODS =
            Set.of("setCharacterEncoding", "addHeader", "getWriter");
    private static final String KOTLIN_PARAMETER_CHECK = "kotlin.jvm.internal.Intrinsics#checkNotNullParameter";

    private OpenApiGeneratorApiUtilFingerprint() {}

    static boolean matches(JavaClass type) {
        if (!NAME.equals(type.getSimpleName())
                || !type.isTopLevelClass()
                || type.isInterface()
                || type.isEnum()
                || type.isRecord()
                || type.isAnnotation()
                || !type.getModifiers().contains(JavaModifier.PUBLIC)
                || !type.getRawSuperclass()
                        .map(superclass -> superclass.getName().equals("java.lang.Object"))
                        .orElse(false)
                || !type.getRawInterfaces().isEmpty()
                || type.getMethods().size() != 1
                || type.getConstructors().size() != 1) {
            return false;
        }
        boolean kotlin = type.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getRawType().getName().equals("kotlin.Metadata"));
        if (type.getAnnotations().size() != (kotlin ? 1 : 0)) return false;
        JavaConstructor constructor = type.getConstructors().iterator().next();
        JavaMethod method = type.getMethods().iterator().next();
        return (kotlin ? kotlinObjectShape(type, constructor) : javaClassShape(type, constructor))
                && exampleResponse(method, kotlin);
    }

    private static boolean javaClassShape(JavaClass type, JavaConstructor constructor) {
        return type.getFields().isEmpty()
                && type.getStaticInitializer().isEmpty()
                && constructor.getModifiers().contains(JavaModifier.PUBLIC)
                && objectConstructorOnly(constructor);
    }

    private static boolean kotlinObjectShape(JavaClass type, JavaConstructor constructor) {
        if (!type.getModifiers().contains(JavaModifier.FINAL)
                || !constructor.getModifiers().contains(JavaModifier.PRIVATE)
                || !objectConstructorOnly(constructor)
                || type.getFields().size() != 1) {
            return false;
        }
        JavaField instance = type.getFields().iterator().next();
        if (!instance.getName().equals("INSTANCE")
                || !instance.getModifiers()
                        .containsAll(Set.of(JavaModifier.PUBLIC, JavaModifier.STATIC, JavaModifier.FINAL))
                || !instance.getRawType().getName().equals(type.getName())) {
            return false;
        }
        return type.getStaticInitializer()
                .map(initializer -> initializer.getMethodCallsFromSelf().isEmpty()
                        && noReferences(initializer)
                        && initializer.getTryCatchBlocks().isEmpty()
                        && initializer.getConstructorCallsFromSelf().size() == 1
                        && initializer.getConstructorCallsFromSelf().stream()
                                .allMatch(
                                        call -> call.getTargetOwner().getName().equals(type.getName())
                                                && call.getTarget()
                                                        .getRawParameterTypes()
                                                        .isEmpty())
                        && initializer.getFieldAccesses().stream()
                                .allMatch(access ->
                                        access.getTargetOwner().getName().equals(type.getName())
                                                && access.getTarget().getName().equals("INSTANCE")))
                .orElse(false);
    }

    private static boolean objectConstructorOnly(JavaConstructor constructor) {
        return constructor.getRawParameterTypes().isEmpty()
                && constructor.getThrowsClause().isEmpty()
                && constructor.getMethodCallsFromSelf().isEmpty()
                && constructor.getFieldAccesses().isEmpty()
                && constructor.getTryCatchBlocks().isEmpty()
                && noReferences(constructor)
                && constructor.getConstructorCallsFromSelf().size() == 1
                && constructor.getConstructorCallsFromSelf().stream()
                        .allMatch(call -> call.getTargetOwner().getName().equals("java.lang.Object")
                                && call.getTarget().getRawParameterTypes().isEmpty());
    }

    private static boolean exampleResponse(JavaMethod method, boolean kotlin) {
        if (!method.getName().equals(METHOD)
                || !method.getModifiers().contains(JavaModifier.PUBLIC)
                || (!kotlin && !method.getModifiers().contains(JavaModifier.STATIC))
                || !method.getRawReturnType().getName().equals("void")
                || !method.getRawParameterTypes().stream()
                        .map(JavaClass::getName)
                        .toList()
                        .equals(PARAMETERS)
                || !method.getThrowsClause().isEmpty()
                || !method.getFieldAccesses().isEmpty()
                || !noReferences(method)) {
            return false;
        }
        if (method.getTryCatchBlocks().size() != 1) return false;
        TryCatchBlock handler = method.getTryCatchBlocks().iterator().next();
        if (handler.getCaughtThrowables().size() != 1
                || !handler.getCaughtThrowables().iterator().next().getName().equals("java.io.IOException")) {
            return false;
        }
        if (method.getConstructorCallsFromSelf().size() != 1
                || !method.getConstructorCallsFromSelf().stream()
                        .allMatch(call -> call.getTargetOwner().getName().equals("java.lang.RuntimeException")
                                && call.getTarget().getRawParameterTypes().stream()
                                        .map(JavaClass::getName)
                                        .toList()
                                        .equals(List.of("java.lang.Throwable")))) {
            return false;
        }
        Set<String> calls = new HashSet<>();
        Set<String> servletResponses = new HashSet<>();
        for (var call : method.getMethodCallsFromSelf()) {
            String owner = call.getTargetOwner().getName();
            String name = call.getName();
            String target = owner + "#" + name;
            boolean allowed = target.equals(GET_NATIVE_RESPONSE)
                    || target.equals(PRINT)
                    || (SERVLET_RESPONSES.contains(owner) && SERVLET_RESPONSE_METHODS.contains(name))
                    || (kotlin && target.equals(KOTLIN_PARAMETER_CHECK));
            if (!allowed) return false;
            if (SERVLET_RESPONSES.contains(owner)) servletResponses.add(owner);
            calls.add(SERVLET_RESPONSES.contains(owner) ? "servlet#" + name : target);
        }
        return servletResponses.size() == 1
                && calls.containsAll(Set.of(
                        GET_NATIVE_RESPONSE,
                        PRINT,
                        "servlet#setCharacterEncoding",
                        "servlet#addHeader",
                        "servlet#getWriter"));
    }

    private static boolean noReferences(JavaCodeUnit codeUnit) {
        return codeUnit.getMethodReferencesFromSelf().isEmpty()
                && codeUnit.getConstructorReferencesFromSelf().isEmpty();
    }
}
