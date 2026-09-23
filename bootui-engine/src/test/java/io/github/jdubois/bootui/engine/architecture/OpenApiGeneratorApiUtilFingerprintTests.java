package io.github.jdubois.bootui.engine.architecture;

import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.compile;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Sourceless runtimes recognise only the exact OpenAPI Generator Spring {@code ApiUtil} template shape. */
class OpenApiGeneratorApiUtilFingerprintTests {
    private static final String JAVAX_STUB = "package javax.servlet.http; public interface HttpServletResponse {"
            + " void setCharacterEncoding(String charset); void addHeader(String name, String value);"
            + " java.io.PrintWriter getWriter() throws java.io.IOException; }";

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
        return template(packageName, servlet, """
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
                """);
    }

    private JavaClasses packaged(Map<String, String> sources) throws IOException {
        Map<String, String> all = new LinkedHashMap<>(sources);
        all.put("stubs/HttpServletResponse.java", JAVAX_STUB);
        compile(root, "extracted", all);
        Path jar = root.resolve("BOOT-INF/lib/app.jar");
        Files.createDirectories(jar.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> files = Files.walk(root.resolve("extracted"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> path.toString().contains("sample"))
                    .sorted()
                    .toList()) {
                out.putNextEntry(new JarEntry(
                        root.resolve("extracted").relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        try (var jarFile = new JarFile(jar.toFile())) {
            return new ClassFileImporter().importJar(jarFile);
        }
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void sixteenPackagedTemplatesDisappearButAHandwrittenGenericThrowRemains(ArchitecturePlatform platform)
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
        var classes = packaged(sources);
        var scanner = new ArchitectureScanner(
                () -> List.of("sample"),
                ignored -> classes,
                platform,
                Clock.systemUTC(),
                List.of(new NoGenericExceptionsRule()),
                ArchitectureGeneratedCode::resolve);

        var report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.evidence().coverageComplete()).isTrue();
        assertThat(report.scan().message())
                .contains("16 class(es) without local source provenance matched the OpenAPI Generator ApiUtil"
                        + " template and were excluded from coding checks.");
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("ARCH-CODE-002");
            assertThat(result.violationCount()).isEqualTo(1);
            assertThat(result.sampleViolations()).singleElement().asString().contains("sample.service.Service");
        });
    }

    @Test
    void theKotlinObjectTemplateIsRecognisedFromRealKotlinBytecode() {
        var classes = new ClassFileImporter()
                .importClasses(io.github.jdubois.bootui.engine.architecture.openapifixtures.ApiUtil.class);
        var type = classes.get(io.github.jdubois.bootui.engine.architecture.openapifixtures.ApiUtil.class);

        assertThat(OpenApiGeneratorApiUtilFingerprint.matches(type)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // Additional generic throw alongside the template's own wrapper.
                """
                        try {
                            HttpServletResponse res = req.getNativeResponse(HttpServletResponse.class);
                            res.setCharacterEncoding("UTF-8");
                            res.addHeader("Content-Type", contentType);
                            res.getWriter().print(example);
                            if (example.isEmpty()) throw new RuntimeException("empty");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                """,
                // Broader handler than the template's IOException.
                """
                        try {
                            HttpServletResponse res = req.getNativeResponse(HttpServletResponse.class);
                            res.setCharacterEncoding("UTF-8");
                            res.addHeader("Content-Type", contentType);
                            res.getWriter().print(example);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                """,
                // Handwritten extra behaviour inside the method.
                """
                        try {
                            HttpServletResponse res = req.getNativeResponse(HttpServletResponse.class);
                            res.setCharacterEncoding("UTF-8");
                            res.addHeader("Content-Type", contentType);
                            res.setStatus(201);
                            res.getWriter().print(example);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                """
            })
    void alteredTemplatesAreStillReported(String body) throws IOException {
        var classes = packaged(Map.of("gen/ApiUtil.java", template("sample.api", "jakarta", body)));

        var result = ArchitectureGeneratedCode.resolve(classes);

        assertThat(classes).hasSize(1);
        assertThat(result.generatedClasses()).isEmpty();
        assertThat(result.templateClasses()).isEmpty();
    }

    @Test
    void extraMembersOrADifferentNameAreStillReported() throws IOException {
        String extraMethod = template("sample.extra", "jakarta")
                .replace(
                        "public class ApiUtil {",
                        "public class ApiUtil { public static void fail() { throw new RuntimeException(); }");
        String extraField = template("sample.field", "jakarta")
                .replace("public class ApiUtil {", "public class ApiUtil { public static int calls;");
        String renamed = template("sample.renamed", "jakarta").replace("class ApiUtil", "class ApiHelper");
        var classes = packaged(Map.of(
                "gen/extra/ApiUtil.java", extraMethod,
                "gen/field/ApiUtil.java", extraField,
                "gen/renamed/ApiHelper.java", renamed));

        var result = ArchitectureGeneratedCode.resolve(classes);

        assertThat(classes).hasSize(3);
        assertThat(result.generatedClasses()).isEmpty();
    }

    @Test
    void localSourceOwnershipStillDecidesWhenAvailable() throws IOException {
        var classes = compile(
                root,
                "target/classes",
                Map.of(
                        "src/main/java/sample/api/ApiUtil.java",
                        template("sample.api", "jakarta"),
                        "src/main/java/javax/servlet/http/HttpServletResponse.java",
                        JAVAX_STUB));

        var result = ArchitectureGeneratedCode.resolve(classes);

        assertThat(result.generatedClasses()).isEmpty();
        assertThat(result.templateClasses()).isEmpty();
    }
}
