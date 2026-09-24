package io.github.jdubois.bootui.engine.architecture;

import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.apiUtil;
import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.cachedJar;
import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.compile;
import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ArchitectureGeneratedCodeTests {
    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({
        "target/classes, target/generated-sources/openapi/src/main/java",
        "target/test-classes, target/generated-test-sources/openapi/src/test/java",
        "build/classes/java/main, build/generated/openapi/src/main/java",
        "build/classes/kotlin/main, build/generated/sources/openapi/main",
        "build/classes/java/test, build/generated/sources/annotationProcessor/java/test",
        "build/classes/java/main, build/generate-resources/main/src/main/java"
    })
    void detectsStandardLayoutsWithoutAssumingPackageDirectories(String output, String generated) throws IOException {
        var classes = compile(root, output, Map.of(generated + "/unrelated/ApiUtil.java", apiUtil("sample.api")));
        var result = ArchitectureGeneratedCode.resolve(classes);
        assertThat(result.generatedClasses()).containsExactly("sample.api.ApiUtil");
        assertThat(result.limitations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jakarta.annotation.Generated",
                "javax.annotation.Generated",
                "javax.annotation.processing.Generated"
            })
    void sourceRetainedAnnotationsAreNotMistakenForBytecodeEvidence(String annotation) throws IOException {
        var classes = compile(
                root,
                "target/classes",
                Map.of(
                        "target/generated-sources/openapi/ApiUtil.java",
                        "package sample; @" + annotation + "(\"generator\") public class ApiUtil {"
                                + " public void run() { throw new RuntimeException(); } }"));
        assertThat(classes.get("sample.ApiUtil").getAnnotations()).isEmpty();
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .containsExactly("sample.ApiUtil");
        Files.delete(root.resolve("target/generated-sources/openapi/ApiUtil.java"));
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .isEmpty();
    }

    @Test
    void aSourceRetainedAnnotationInHandwrittenSourcesDoesNotExemptTheClass() throws IOException {
        var classes = compile(
                root,
                "target/classes",
                Map.of(
                        "src/main/java/ApiUtil.java",
                        "package sample; @jakarta.annotation.Generated(\"generator\") "
                                + "public class ApiUtil { public void run() { throw new RuntimeException(); } }"));
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .isEmpty();
    }

    @Test
    void generatedNestedTypesAreExemptButHandwrittenSubclassesAndCallersAreNot() throws IOException {
        var classes = compile(
                root,
                "target/classes",
                Map.of(
                        "target/generated-sources/openapi/ApiUtil.java",
                        "package sample.api; public class ApiUtil { public static class Nested {"
                                + " public void run() { throw new RuntimeException(); } } }",
                        "src/main/java/Caller.java",
                        "package sample.api; class Caller extends ApiUtil.Nested {"
                                + " void call() { new ApiUtil.Nested().run(); throw new RuntimeException(); } }"));
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .containsExactlyInAnyOrder("sample.api.ApiUtil", "sample.api.ApiUtil$Nested");
    }

    @Test
    void sameNamedFilesInOtherPackagesOrModulesDoNotEstablishProvenance() throws IOException {
        var first = compile(
                root.resolve("one"),
                "target/classes",
                Map.of("target/generated-sources/openapi/ApiUtil.java", apiUtil("sample.one")));
        var second = compile(
                root.resolve("two"), "target/classes", Map.of("src/main/java/ApiUtil.java", apiUtil("sample.two")));
        write(root.resolve("two/target/generated-sources/openapi/ApiUtil.java"), apiUtil("other"));
        var classes = new ClassFileImporter()
                .importPaths(root.resolve("one/target/classes"), root.resolve("two/target/classes"));
        assertThat(classes).hasSize(first.size() + second.size());
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .containsExactly("sample.one.ApiUtil");
    }

    @Test
    void staleOrAmbiguousSourceOwnershipRetainsFindings() throws IOException {
        var classes = compile(
                root, "target/classes", Map.of("target/generated-sources/openapi/ApiUtil.java", apiUtil("sample")));
        Path duplicate = write(root.resolve("target/generated-sources/another/ApiUtil.java"), apiUtil("sample"));
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .isEmpty();
        Files.delete(duplicate);
        Path stale = write(
                root.resolve("src/main/kotlin/DifferentFilename.kt"),
                "package sample\nclass ApiUtil { fun run() { throw RuntimeException() } }");
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .isEmpty();
        Files.delete(stale);
        write(root.resolve("target/generated-sources/openapi/ApiUtil.java"), "package sample; class Different {}");
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .isEmpty();
    }

    @Test
    void doesNotUseTestGenerationForMainClasses() throws IOException {
        var classes = compile(
                root,
                "build/classes/java/main",
                Map.of("build/generated/sources/tool/test/ApiUtil.java", apiUtil("sample")));
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "build/classes/java/main, build/generated/openapi/src/main/java/com/acme/test, com.acme.test",
        "build/classes/java/test, build/generated/openapi/src/test/java/com/acme/main, com.acme.main",
        "build/classes/java/main, build/generated/sources/openapi/main/com/acme/test, com.acme.test",
        "build/classes/java/main, build/generated/openapi/src/main/java/unrelated/test, com.acme.api"
    })
    void packageDirectoryNamesAreNotSourceSets(String output, String generated, String packageName) throws IOException {
        var classes = compile(root, output, Map.of(generated + "/ApiUtil.java", apiUtil(packageName)));
        var result = ArchitectureGeneratedCode.resolve(classes);
        assertThat(result.generatedClasses()).containsExactly(packageName + ".ApiUtil");
        assertThat(result.limitations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sources", "src/main/java", "custom/kotlin"})
    void staleGeneratedFilesDoNotExemptAuthoredClassesInCustomOrMultilineSources(String sourceRoot) throws IOException {
        var classes = compile(
                root,
                "target/classes",
                Map.of(sourceRoot + "/ApiUtil.java", apiUtil("sample").replace("class ApiUtil", "class\nApiUtil")));
        write(root.resolve("target/generated-sources/openapi/ApiUtil.java"), apiUtil("sample"));
        var report = new ArchitectureScanner(
                        () -> List.of("sample"),
                        ignored -> classes,
                        ArchitecturePlatform.SPRING,
                        Clock.systemUTC(),
                        List.of(new NoGenericExceptionsRule()))
                .scan();
        assertThat(report.results()).singleElement().satisfies(rule -> {
            assertThat(rule.violationCount()).isEqualTo(1);
            assertThat(rule.sampleViolations()).singleElement().asString().contains("sample.ApiUtil");
        });
        assertThat(report.violationDetails().total()).isEqualTo(1);
    }

    @Test
    void ordinaryUnicodeInUnrelatedJavaSourcesDoesNotDiscardGeneratedMatches() throws IOException {
        String escape = "\\" + "u";
        var classes = compile(
                root,
                "target/classes",
                Map.of(
                        "target/generated-sources/openapi/ApiUtil.java",
                        apiUtil("sample"),
                        "src/main/java/Unrelated.java",
                        "package sample; class Unrelated { String label = \"" + escape + "2026\"; }"));
        var result = ArchitectureGeneratedCode.resolve(classes);
        assertThat(result.generatedClasses()).containsExactly("sample.ApiUtil");
        assertThat(result.limitations()).isEmpty();
    }

    @Test
    void unicodeEscapedAuthoredDeclarationsStillBlockStaleGeneratedMatches() throws IOException {
        String escape = "\\" + "u";
        var classes = compile(
                root,
                "target/classes",
                Map.of(
                        "src/main/java/ApiUtil.java",
                        "package sample; // comment" + escape + "000d public class Api" + escape
                                + "0000Util { public void run() { throw new RuntimeException(); } }"));
        write(root.resolve("target/generated-sources/openapi/ApiUtil.java"), apiUtil("sample"));
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .isEmpty();
    }

    @Test
    void externalCompilerInputsRemainUnclassifiedWithoutReadingOutsideTheModule() throws IOException {
        Path module = root.resolve("module");
        var classes = compile(
                module, "target/classes", Map.of("target/generated-sources/openapi/ApiUtil.java", apiUtil("sample")));
        write(
                module.resolve("target/maven-status/maven-compiler-plugin/compile/default-compile/inputFiles.lst"),
                root.resolve("external/ApiUtil.java").toString());
        var result = ArchitectureGeneratedCode.resolve(classes);
        assertThat(result.generatedClasses()).isEmpty();
        assertThat(result.limitations())
                .singleElement()
                .asString()
                .contains("IOException")
                .doesNotContain(root.toString());
    }

    @Test
    void unknownOutputLayoutsAndSourceFreeJarsRetainClasses() throws IOException {
        var classes = compile(
                root, "custom-output", Map.of("target/generated-sources/openapi/ApiUtil.java", apiUtil("sample")));
        assertThat(ArchitectureGeneratedCode.resolve(classes).generatedClasses())
                .isEmpty();
        Path jar = root.resolve("app.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("sample/ApiUtil.class"));
            Files.copy(root.resolve("custom-output/sample/ApiUtil.class"), out);
            out.closeEntry();
        }
        try (JarFile jarFile = cachedJar(jar)) {
            var packaged = new ClassFileImporter().importJar(jarFile);
            assertThat(packaged).hasSize(1);
            assertThat(ArchitectureGeneratedCode.resolve(packaged).generatedClasses())
                    .isEmpty();
        }
    }

    @Test
    void rejectsSourceFileTraversalWithoutInspectingAnOutsideFile() throws IOException {
        compile(root, "target/classes", Map.of("target/generated-sources/openapi/ApiUtil.java", apiUtil("sample")));
        Path file = root.resolve("target/classes/sample/ApiUtil.class");
        byte[] bytes = Files.readAllBytes(file);
        byte[] original = "ApiUtil.java".getBytes(StandardCharsets.UTF_8);
        byte[] traversal = "../evil.java".getBytes(StandardCharsets.UTF_8);
        assertThat(original).hasSameSizeAs(traversal);
        boolean replaced = false;
        for (int i = 0; i <= bytes.length - original.length; i++) {
            if (java.util.Arrays.equals(bytes, i, i + original.length, original, 0, original.length)) {
                System.arraycopy(traversal, 0, bytes, i, traversal.length);
                replaced = true;
            }
        }
        assertThat(replaced).isTrue();
        Files.write(file, bytes);
        var result = ArchitectureGeneratedCode.resolve(new ClassFileImporter().importPath(file));
        assertThat(result.generatedClasses()).isEmpty();
        assertThat(result.limitations()).isEmpty();
    }

    @Test
    void symlinksAndUnreadableSourceOwnershipRetainTheEntireUncertainModule() throws IOException {
        var classes = compile(
                root, "target/classes", Map.of("target/generated-sources/openapi/ApiUtil.java", apiUtil("sample")));
        Path link = root.resolve("src/main/java/elsewhere");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, root.resolve("target/generated-sources"));
        var result = ArchitectureGeneratedCode.resolve(classes);
        assertThat(result.generatedClasses()).isEmpty();
        assertThat(result.limitations())
                .singleElement()
                .asString()
                .contains("IOException")
                .doesNotContain(root.toString());
        Files.delete(link);
        Files.write(root.resolve("target/generated-sources/openapi/ApiUtil.java"), new byte[] {(byte) 0xff});
        result = ArchitectureGeneratedCode.resolve(classes);
        assertThat(result.generatedClasses()).isEmpty();
        assertThat(result.limitations()).singleElement().asString().contains("MalformedInputException");
    }

    @Test
    void pinsAllBudgetsAndTheirBoundaryBehavior() throws IOException {
        assertThat(ArchitectureGeneratedCode.DEFAULT_LIMITS)
                .isEqualTo(new ArchitectureGeneratedCode.Limits(64, 50_000, 32, 262_144, 16_777_216));
        String source = apiUtil("sample");
        var classes = compile(root, "target/classes", Map.of("target/generated-sources/openapi/ApiUtil.java", source));
        int size = source.getBytes(StandardCharsets.UTF_8).length;
        var exact = new ArchitectureGeneratedCode.Limits(1, 5, 1, size, size);
        assertThat(ArchitectureGeneratedCode.resolve(classes, exact).generatedClasses())
                .containsExactly("sample.ApiUtil");
        for (var limits : List.of(
                new ArchitectureGeneratedCode.Limits(1, 1, 1, size, size),
                new ArchitectureGeneratedCode.Limits(1, 5, 1, size - 1, size),
                new ArchitectureGeneratedCode.Limits(1, 5, 1, size, size - 1))) {
            var result = ArchitectureGeneratedCode.resolve(classes, limits);
            assertThat(result.generatedClasses()).isEmpty();
            assertThat(result.limitations()).singleElement().asString().contains("limit reached");
        }
        Files.createDirectories(root.resolve("target/generated-sources/openapi/deeper"));
        var depth =
                ArchitectureGeneratedCode.resolve(classes, new ArchitectureGeneratedCode.Limits(1, 10, 1, size, size));
        assertThat(depth.generatedClasses()).isEmpty();
        assertThat(depth.limitations()).singleElement().asString().contains("depth limit");
    }

    @Test
    void capsModulesAndAggregateSourceBytesWithoutDiscardingCompletedModules() throws IOException {
        compile(root.resolve("one"), "target/classes", Map.of("target/generated-sources/ApiUtil.java", apiUtil("one")));
        compile(root.resolve("two"), "target/classes", Map.of("target/generated-sources/ApiUtil.java", apiUtil("two")));
        var classes = new ClassFileImporter()
                .importPaths(root.resolve("one/target/classes"), root.resolve("two/target/classes"));
        var moduleLimit =
                ArchitectureGeneratedCode.resolve(classes, new ArchitectureGeneratedCode.Limits(1, 20, 2, 1024, 2048));
        assertThat(moduleLimit.generatedClasses()).containsExactly("one.ApiUtil");
        assertThat(moduleLimit.limitations()).singleElement().asString().contains("module limit");
        int size = apiUtil("one").getBytes(StandardCharsets.UTF_8).length;
        var byteLimit =
                ArchitectureGeneratedCode.resolve(classes, new ArchitectureGeneratedCode.Limits(2, 20, 2, 1024, size));
        assertThat(byteLimit.generatedClasses()).containsExactly("one.ApiUtil");
        assertThat(byteLimit.limitations()).singleElement().asString().contains("total-byte limit");
    }
}
