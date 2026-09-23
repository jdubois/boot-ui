package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.Source;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Exact bytecode fingerprint of the OpenAPI Generator Spring (servlet) {@code ApiUtil} template, for Java
 * ({@code JavaSpring/apiUtil.mustache}) and Kotlin ({@code kotlin-spring/apiUtil.mustache}).
 *
 * <p>The template carries no bytecode-retained marker, so a packaged application without a source tree cannot
 * prove provenance. Class members are checked structurally, and the complete instruction stream of
 * {@code setExampleResponse}, including constants, call descriptors, local slots, branches and the exception
 * table, must equal the verified output of a known compiler. Any other compiler output keeps its findings.
 * Types are compared by name only, so neither Spring nor a servlet API is loaded.</p>
 */
final class OpenApiGeneratorApiUtilFingerprint {
    static final int MAX_CLASS_BYTES = 64 * 1024;
    private static final String NAME = "ApiUtil";
    private static final String METHOD = "setExampleResponse";
    private static final String DESCRIPTOR =
            "(Lorg/springframework/web/context/request/NativeWebRequest;Ljava/lang/String;Ljava/lang/String;)V";
    private static final List<String> PARAMETERS =
            List.of("org.springframework.web.context.request.NativeWebRequest", "java.lang.String", "java.lang.String");
    private static final String JAKARTA_SERVLET = "jakarta/servlet/";
    private static final String JAVAX_SERVLET = "javax/servlet/";

    // SHA-256 of the normalized instructions (see ApiUtilBytecode), with javax.servlet unified to jakarta.servlet.
    // The full normalized forms are pinned by test resources under openapi-apiutil/.
    /** javac, identical for --release 8 through 26. */
    private static final Set<String> JAVA_VARIANTS =
            Set.of("62abb65ff0070a37e44bf180689a828ffc09cb63c476ee3b804c41da2939a6e4");

    private static final Set<String> KOTLIN_VARIANTS = Set.of(
            // kotlinc 1.3.72
            "96654a3c5d434dd785e96eb917875462312e8e66152c97203a747ff7df6eccf4",
            // kotlinc 1.5.32
            "a0138804edff993bd7360b53f78f755969f506d79d31bf5d9f73d9a4bcc152be",
            // kotlinc 1.6.21, 1.8.22, 1.9.25
            "e3134da7d73fcdc32e629c49b9b81028a79f6ba1816bc7544e7a6eefc6b15e40",
            // kotlinc 2.0.21, 2.4.10
            "b137534082590b97e34c2bc434424cbbab3338c0803ce50c51d62febfdbff9e7");

    private OpenApiGeneratorApiUtilFingerprint() {}

    /** Matches a class imported from a {@code jar:} location, reading its class file from that location. */
    static boolean matches(JavaClass type) {
        return matches(type, OpenApiGeneratorApiUtilFingerprint::packagedClassFile);
    }

    static boolean matches(JavaClass type, Function<JavaClass, Optional<byte[]>> classFile) {
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
        if (!(kotlin ? kotlinObjectShape(type, constructor) : javaClassShape(type, constructor))
                || !exampleResponseSignature(method, kotlin)) {
            return false;
        }
        Optional<String> digest;
        try {
            digest = classFile.apply(type).flatMap(OpenApiGeneratorApiUtilFingerprint::digest);
        } catch (RuntimeException ex) {
            return false;
        }
        return digest.filter(kotlin ? KOTLIN_VARIANTS::contains : JAVA_VARIANTS::contains)
                .isPresent();
    }

    static Set<String> variants() {
        Set<String> variants = new HashSet<>(JAVA_VARIANTS);
        variants.addAll(KOTLIN_VARIANTS);
        return Set.copyOf(variants);
    }

    /** Digest of the normalized {@code setExampleResponse} instructions, or empty for mixed servlet APIs. */
    static Optional<String> digest(byte[] classFile) {
        return ApiUtilBytecode.instructions(classFile, METHOD, DESCRIPTOR).flatMap(tokens -> {
            String joined = String.join("\n", tokens);
            if (joined.contains(JAKARTA_SERVLET) && joined.contains(JAVAX_SERVLET)) return Optional.empty();
            return Optional.of(sha256(joined.replace(JAVAX_SERVLET, JAKARTA_SERVLET)));
        });
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Optional<byte[]> packagedClassFile(JavaClass type) {
        Optional<Source> source = type.getSource();
        if (source.isEmpty() || !packaged(source.get().getUri())) return Optional.empty();
        try {
            URLConnection connection = source.get().getUri().toURL().openConnection();
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = in.readNBytes(MAX_CLASS_BYTES + 1);
                return bytes.length > MAX_CLASS_BYTES ? Optional.empty() : Optional.of(bytes);
            }
        } catch (IOException | IllegalArgumentException | SecurityException ex) {
            return Optional.empty();
        }
    }

    /** A class read from inside an archive, as in executable jars and extracted {@code BOOT-INF/lib} layouts. */
    static boolean packaged(URI uri) {
        return "jar".equals(uri.getScheme());
    }

    private static boolean javaClassShape(JavaClass type, JavaConstructor constructor) {
        return type.getFields().isEmpty()
                && type.getStaticInitializer().isEmpty()
                && constructor.getModifiers().contains(JavaModifier.PUBLIC)
                && objectConstructorOnly(constructor)
                && sourceFile(type, NAME + ".java");
    }

    private static boolean kotlinObjectShape(JavaClass type, JavaConstructor constructor) {
        if (!type.getModifiers().contains(JavaModifier.FINAL)
                || !constructor.getModifiers().contains(JavaModifier.PRIVATE)
                || !objectConstructorOnly(constructor)
                || !sourceFile(type, NAME + ".kt")
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
                        && initializer.getMethodReferencesFromSelf().isEmpty()
                        && initializer.getConstructorReferencesFromSelf().isEmpty()
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

    private static boolean sourceFile(JavaClass type, String expected) {
        return type.getSource()
                .flatMap(Source::getFileName)
                .filter(expected::equals)
                .isPresent();
    }

    private static boolean objectConstructorOnly(JavaConstructor constructor) {
        return constructor.getRawParameterTypes().isEmpty()
                && constructor.getThrowsClause().isEmpty()
                && constructor.getMethodCallsFromSelf().isEmpty()
                && constructor.getFieldAccesses().isEmpty()
                && constructor.getTryCatchBlocks().isEmpty()
                && constructor.getMethodReferencesFromSelf().isEmpty()
                && constructor.getConstructorReferencesFromSelf().isEmpty()
                && constructor.getConstructorCallsFromSelf().size() == 1
                && constructor.getConstructorCallsFromSelf().stream()
                        .allMatch(call -> call.getTargetOwner().getName().equals("java.lang.Object")
                                && call.getTarget().getRawParameterTypes().isEmpty());
    }

    private static boolean exampleResponseSignature(JavaMethod method, boolean kotlin) {
        return method.getName().equals(METHOD)
                && method.getModifiers().contains(JavaModifier.PUBLIC)
                && method.getModifiers().contains(JavaModifier.STATIC) != kotlin
                && method.getRawReturnType().getName().equals("void")
                && method.getRawParameterTypes().stream()
                        .map(JavaClass::getName)
                        .toList()
                        .equals(PARAMETERS)
                && method.getThrowsClause().isEmpty();
    }
}
