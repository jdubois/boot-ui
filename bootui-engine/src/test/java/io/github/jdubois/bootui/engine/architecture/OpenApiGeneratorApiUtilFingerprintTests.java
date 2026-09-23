package io.github.jdubois.bootui.engine.architecture;

import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.compile;
import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.engine.archunit.ArchUnitClassImports;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Sourceless runtimes recognise only the exact OpenAPI Generator Spring {@code ApiUtil} template bytecode. */
class OpenApiGeneratorApiUtilFingerprintTests {
    private static final String JAVAX_STUB = "package javax.servlet.http; public interface HttpServletResponse {"
            + " void setCharacterEncoding(String charset); void addHeader(String name, String value);"
            + " java.io.PrintWriter getWriter() throws java.io.IOException; }";
    private static final String DESCRIPTOR =
            "(Lorg/springframework/web/context/request/NativeWebRequest;Ljava/lang/String;Ljava/lang/String;)V";
    private static final String BODY = """
                    try {
                        HttpServletResponse res = req.getNativeResponse(HttpServletResponse.class);
                        if (res != null) {
                            res.setCharacterEncoding("UTF-8");
                            res.addHeader("Content-Type", contentType);
                            res.getWriter().print(example);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
            """;

    @TempDir
    Path root;

    /** Verbatim JavaSpring/apiUtil.mustache output for a servlet (non-reactive) generator run. */
    static String template(String packageName, String servlet, String body) {
        return """
                package %s;

                import org.springframework.web.context.request.NativeWebRequest;

                import %s.servlet.http.HttpServletResponse;
                import java.io.IOException;

                public class ApiUtil {
                    public static void setExampleResponse(NativeWebRequest req, String contentType, String example) {
                %s
                    }
                }
                """.formatted(packageName, servlet, body);
    }

    static String template(String packageName, String servlet) {
        return template(packageName, servlet, BODY);
    }

    private static List<String> variant(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                OpenApiGeneratorApiUtilFingerprintTests.class.getResourceAsStream("openapi-apiutil/" + name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    private static List<String> normalized(byte[] classFile) {
        return ApiUtilBytecode.instructions(classFile, "setExampleResponse", DESCRIPTOR).orElseThrow().stream()
                .map(token -> token.replace("javax/servlet/", "jakarta/servlet/"))
                .toList();
    }

    /** Compiles the sources and packages every {@code sample} class into an extracted-layout library jar. */
    private Path jar(Map<String, String> sources) throws IOException {
        Map<String, String> all = new LinkedHashMap<>(sources);
        all.put("stubs/HttpServletResponse.java", JAVAX_STUB);
        compile(root, "extracted", all);
        Path classes = root.resolve("extracted");
        Path jar = root.resolve("app/BOOT-INF/lib/api.jar");
        Files.createDirectories(jar.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> classes.relativize(path).startsWith("sample"))
                    .sorted()
                    .toList()) {
                out.putNextEntry(
                        new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    private static JavaClasses importJar(Path jar) throws IOException {
        try (var jarFile = new JarFile(jar.toFile())) {
            return new ClassFileImporter().importJar(jarFile);
        }
    }

    private JavaClasses packaged(Map<String, String> sources) throws IOException {
        return importJar(jar(sources));
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void sixteenPackagedTemplatesDisappearThroughTheProductionImporter(ArchitecturePlatform platform)
            throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < 16; i++) {
            sources.put(
                    "gen/module" + i + "/ApiUtil.java",
                    template("sample.module" + i + ".api", i % 2 == 0 ? "jakarta" : "javax"));
        }
        sources.put(
                "src/Service.java",
                "package sample.service; public class Service { void run() { throw new RuntimeException(); } }");
        Path jar = jar(sources);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            var scanner = new ArchitectureScanner(
                    () -> List.of("sample"),
                    packages -> {
                        JavaClasses imported = ArchUnitClassImports.importPackages(packages);
                        assertThat(imported).hasSize(17);
                        assertThat(imported.stream()
                                        .map(type -> type.getSource()
                                                .orElseThrow()
                                                .getUri()
                                                .getScheme()))
                                .containsOnly("jar");
                        return imported;
                    },
                    platform,
                    Clock.systemUTC(),
                    List.of(new NoGenericExceptionsRule()));

            var report = scanner.scan();

            assertThat(report.scan().status()).isEqualTo("SCANNED");
            assertThat(report.classesAnalyzed()).isEqualTo(17);
            assertThat(report.evidence().coverageComplete()).isTrue();
            assertThat(report.scan().message())
                    .contains("16 class(es) without local source provenance matched the OpenAPI Generator ApiUtil"
                            + " template and were excluded from coding checks.");
            assertThat(report.results()).singleElement().satisfies(result -> {
                assertThat(result.id()).isEqualTo("ARCH-CODE-002");
                assertThat(result.violationCount()).isEqualTo(1);
                assertThat(result.sampleViolations()).singleElement().asString().contains("sample.service.Service");
            });
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"8", "11", "17", "21"})
    void javacOutputForEveryReleaseEqualsThePinnedVariant(String release) throws IOException {
        Path source = write(root.resolve("src/sample/api/ApiUtil.java"), template("sample.api", "javax"));
        Path stub = write(root.resolve("src/javax/servlet/http/HttpServletResponse.java"), JAVAX_STUB);
        Path out = Files.createDirectories(root.resolve("out"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(
                null,
                null,
                null,
                "--release",
                release,
                "-nowarn",
                "-Xlint:-options",
                "-classpath",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "-d",
                out.toString(),
                source.toString(),
                stub.toString());
        assertThat(status).isZero();

        byte[] classFile = Files.readAllBytes(out.resolve("sample/api/ApiUtil.class"));

        assertThat(normalized(classFile)).containsExactlyElementsOf(variant("javac.txt"));
        assertThat(OpenApiGeneratorApiUtilFingerprint.digest(classFile))
                .hasValueSatisfying(digest -> assertThat(OpenApiGeneratorApiUtilFingerprint.variants())
                        .contains(digest));
    }

    @Test
    void everyPinnedVariantHasItsDigestAndNoOtherDigestIsAccepted() throws IOException {
        Set<String> digests = Stream.of(
                        "javac.txt", "kotlin-1.3.txt", "kotlin-1.5.txt", "kotlin-1.6-1.9.txt", "kotlin-2.txt")
                .map(name -> {
                    try {
                        return OpenApiGeneratorApiUtilFingerprint.sha256(String.join("\n", variant(name)));
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .collect(Collectors.toSet());

        assertThat(digests).hasSize(5).isEqualTo(OpenApiGeneratorApiUtilFingerprint.variants());
    }

    @Test
    void theKotlinObjectTemplateIsRecognisedWhenPackaged() throws IOException {
        Class<?> template = io.github.jdubois.bootui.engine.architecture.openapifixtures.ApiUtil.class;
        Class<?> altered = io.github.jdubois.bootui.engine.architecture.openapifixtures.altered.ApiUtil.class;
        Path jar = root.resolve("app/BOOT-INF/lib/kotlin-api.jar");
        Files.createDirectories(jar.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Class<?> type : List.of(template, altered)) {
                String entry = type.getName().replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(entry));
                try (InputStream in =
                        Objects.requireNonNull(type.getClassLoader().getResourceAsStream(entry))) {
                    byte[] bytes = in.readAllBytes();
                    if (type == template) {
                        assertThat(normalized(bytes)).containsExactlyElementsOf(variant("kotlin-2.txt"));
                    }
                    out.write(bytes);
                }
                out.closeEntry();
            }
        }

        var result = ArchitectureGeneratedCode.resolve(importJar(jar));

        assertThat(result.generatedClasses()).containsExactly(template.getName());
        assertThat(result.templateClasses()).containsExactly(template.getName());
    }

    static Stream<String> alteredBodies() {
        return Stream.of(
                // Changed constant.
                BODY.replace("\"UTF-8\"", "\"US-ASCII\""),
                // Changed header name.
                BODY.replace("\"Content-Type\"", "\"X-Content-Type\""),
                // Different argument wiring.
                BODY.replace("print(example)", "print(contentType)"),
                // Duplicated allowed call.
                BODY.replace(
                        "res.setCharacterEncoding(\"UTF-8\");",
                        "res.setCharacterEncoding(\"UTF-8\"); res.setCharacterEncoding(\"UTF-8\");"),
                // Missing required call.
                BODY.replace("res.addHeader(\"Content-Type\", contentType);", ""),
                // Missing null guard.
                BODY.replace("if (res != null) {", "{"),
                // Conditional wrapper throw in the handler.
                BODY.replace(
                        "throw new RuntimeException(e);", "if (contentType == null) throw new RuntimeException(e);"),
                // Broader handler.
                BODY.replace("catch (IOException e)", "catch (Exception e)"),
                // Additional generic throw.
                BODY.replace(
                        "res.getWriter().print(example);",
                        "res.getWriter().print(example); if (example.isEmpty()) throw new RuntimeException(\"x\");"),
                // Additional servlet call.
                BODY.replace("res.getWriter()", "res.getOutputStream(); res.getWriter()"));
    }

    @ParameterizedTest
    @MethodSource("alteredBodies")
    void everyAlteredTemplateBodyIsStillReported(String body) throws IOException {
        var classes = packaged(Map.of("gen/ApiUtil.java", template("sample.api", "jakarta", body)));

        var result = ArchitectureGeneratedCode.resolve(classes);

        assertThat(classes).hasSize(1);
        assertThat(result.generatedClasses()).isEmpty();
        assertThat(result.templateClasses()).isEmpty();
    }

    @Test
    void extraMembersInterfacesOrADifferentNameAreStillReported() throws IOException {
        String header = "public class ApiUtil {";
        var classes = packaged(Map.of(
                "gen/extra/ApiUtil.java",
                        template("sample.extra", "jakarta")
                                .replace(
                                        header,
                                        header + " public static void fail() { throw new RuntimeException(); }"),
                "gen/field/ApiUtil.java",
                        template("sample.field", "jakarta").replace(header, header + " public static int calls;"),
                "gen/iface/ApiUtil.java",
                        template("sample.iface", "jakarta")
                                .replace(header, "public class ApiUtil implements java.io.Serializable {"),
                "gen/renamed/ApiHelper.java",
                        template("sample.renamed", "jakarta").replace("class ApiUtil", "class ApiHelper")));

        var result = ArchitectureGeneratedCode.resolve(classes);

        assertThat(classes).hasSize(4);
        assertThat(result.generatedClasses()).isEmpty();
    }

    @Test
    void mixedServletApisDoNotMatch() throws IOException {
        String mixed = template(
                "sample.api",
                "jakarta",
                BODY.replace(
                        "res.addHeader(\"Content-Type\", contentType);",
                        "((javax.servlet.http.HttpServletResponse) (Object) res).addHeader(\"Content-Type\","
                                + " contentType);"));
        var classes = packaged(Map.of("gen/ApiUtil.java", mixed));

        assertThat(classes).hasSize(1);
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .isEmpty();
    }

    @Test
    void theReactiveTemplateIsNotPartOfTheFingerprint() throws IOException {
        var classes = packaged(Map.of("gen/ApiUtil.java", """
                package sample.api;

                import java.nio.charset.StandardCharsets;
                import org.springframework.core.io.buffer.DefaultDataBuffer;
                import org.springframework.core.io.buffer.DefaultDataBufferFactory;
                import org.springframework.http.MediaType;
                import org.springframework.http.server.reactive.ServerHttpResponse;
                import org.springframework.web.server.ServerWebExchange;
                import reactor.core.publisher.Mono;

                public class ApiUtil {
                    public static Mono<Void> getExampleResponse(ServerWebExchange exchange, MediaType mediaType,
                            String example) {
                        ServerHttpResponse response = exchange.getResponse();
                        response.getHeaders().setContentType(mediaType);

                        byte[] exampleBytes = example.getBytes(StandardCharsets.UTF_8);
                        DefaultDataBuffer data = new DefaultDataBufferFactory().wrap(exampleBytes);
                        return response.writeWith(Mono.just(data));
                    }
                }
                """));

        assertThat(classes).hasSize(1);
        assertThat(ArchitectureGeneratedCode.resolve(classes).templateClasses()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"target/classes", "custom-output"})
    void localClassesNeverUseTheFingerprint(String output) throws IOException {
        var classes = compile(
                root,
                output,
                Map.of(
                        "src/main/java/sample/api/ApiUtil.java",
                        template("sample.api", "jakarta"),
                        "src/main/java/javax/servlet/http/HttpServletResponse.java",
                        JAVAX_STUB));

        var result = ArchitectureGeneratedCode.resolve(classes);

        assertThat(classes.contain("sample.api.ApiUtil")).isTrue();
        assertThat(result.generatedClasses()).isEmpty();
        assertThat(result.templateClasses()).isEmpty();
    }
}
