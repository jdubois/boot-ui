package io.github.jdubois.bootui.engine.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceLocatorTests {

    @TempDir
    Path workspace;

    private Path write(String relative, String content) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private URI classFile(String relative) throws IOException {
        return write(relative, "cafebabe").toUri();
    }

    private static SourceLocator.Request request(String className, URI classFile, String sourceFile) {
        return new SourceLocator.Request(className, classFile, sourceFile);
    }

    @Test
    void resolvesAMavenMainClassToItsOneSourceFileWithItsLineCount() throws IOException {
        Path source = write("shop/src/main/java/com/example/OrderService.java", """
                package com.example;

                public class OrderService {
                }
                """);
        URI compiled = classFile("shop/target/classes/com/example/OrderService.class");

        SourceLocator.Result result =
                SourceLocator.resolve(List.of(request("com.example.OrderService", compiled, "OrderService.java")));

        assertThat(result.get("com.example.OrderService")).hasValueSatisfying(resolved -> {
            assertThat(resolved.path()).isEqualTo(source.toAbsolutePath().normalize());
            assertThat(resolved.lineCount()).isEqualTo(4);
        });
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void resolvesNestedClassesAndMavenTestSourcesInAMultiModuleTree() throws IOException {
        write("app/module-a/src/main/java/com/example/Order.java", "package com.example; class Order {}");
        Path test = write(
                "app/module-a/src/test/java/com/example/OrderTest.java", "package com.example; class OrderTest {}");
        write("app/module-b/src/test/java/com/example/OrderTest.java", "package com.example; class OrderTest {}");
        URI nested = classFile("app/module-a/target/test-classes/com/example/OrderTest$Fixture.class");

        SourceLocator.Result result =
                SourceLocator.resolve(List.of(request("com.example.OrderTest$Fixture", nested, "OrderTest.java")));

        assertThat(result.get("com.example.OrderTest$Fixture").orElseThrow().path())
                .isEqualTo(test.toAbsolutePath().normalize());
    }

    @Test
    void resolvesGradleKotlinSourcesWhoseDirectoriesOmitThePackagePrefix() throws IOException {
        Path source = write("svc/src/main/kotlin/orders/Handlers.kt", """
                package com.example.orders

                fun handle() = Unit
                """);
        URI facade = classFile("svc/build/classes/kotlin/main/com/example/orders/HandlersKt.class");

        SourceLocator.Result result =
                SourceLocator.resolve(List.of(request("com.example.orders.HandlersKt", facade, "Handlers.kt")));

        assertThat(result.get("com.example.orders.HandlersKt").orElseThrow().path())
                .isEqualTo(source.toAbsolutePath().normalize());
    }

    @Test
    void resolvesGeneratedSourcesButOnlyForTheClassSourceSet() throws IOException {
        Path main = write(
                "svc/build/generated/sources/annotationProcessor/java/main/com/example/MapperImpl.java",
                "package com.example; class MapperImpl {}");
        write(
                "svc/build/generated/sources/annotationProcessor/java/test/com/example/MapperImpl.java",
                "package com.example; class MapperImpl {}");
        URI compiled = classFile("svc/build/classes/java/main/com/example/MapperImpl.class");

        SourceLocator.Result result =
                SourceLocator.resolve(List.of(request("com.example.MapperImpl", compiled, "MapperImpl.java")));

        assertThat(result.get("com.example.MapperImpl").orElseThrow().path())
                .isEqualTo(main.toAbsolutePath().normalize());
    }

    @Test
    void keepsNoPathWhenTwoSourceFilesDeclareTheSameClassFile() throws IOException {
        write("shop/src/main/java/com/example/Order.java", "package com.example; class Order {}");
        write(
                "shop/target/generated-sources/annotations/com/example/Order.java",
                "package com.example; class Order {}");
        URI compiled = classFile("shop/target/classes/com/example/Order.class");

        SourceLocator.Result result =
                SourceLocator.resolve(List.of(request("com.example.Order", compiled, "Order.java")));

        assertThat(result.get("com.example.Order")).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("matched more than one source file");
    }

    @Test
    void ignoresSameNamedFilesOfAnotherPackage() throws IOException {
        Path source = write("shop/src/main/java/com/example/Order.java", "package com.example; class Order {}");
        write("shop/src/main/java/com/other/Order.java", "package com.other; class Order {}");
        URI compiled = classFile("shop/target/classes/com/example/Order.class");

        assertThat(SourceLocator.resolve(List.of(request("com.example.Order", compiled, "Order.java")))
                        .get("com.example.Order")
                        .orElseThrow()
                        .path())
                .isEqualTo(source.toAbsolutePath().normalize());
    }

    @Test
    void archivesPackagedLayoutsAndMissingMetadataKeepNoPathWithANote() throws IOException {
        write("app/BOOT-INF/classes/com/example/Order.java", "package com.example; class Order {}");
        List<SourceLocator.Request> requests = List.of(
                request(
                        "com.example.Jar",
                        URI.create("jar:file:/app/app.jar!/BOOT-INF/classes!/com/example/Jar.class"),
                        "Jar.java"),
                request(
                        "com.example.Nested",
                        URI.create("jar:nested:/app/app.jar/!BOOT-INF/lib/lib.jar!/com/example/Nested.class"),
                        "Nested.java"),
                request(
                        "com.example.QuarkusLib",
                        URI.create("jar:file:/app/target/quarkus-app/lib/main/app.jar!/com/example/QuarkusLib.class"),
                        "QuarkusLib.java"),
                request("com.example.Order", classFile("app/BOOT-INF/classes/com/example/Order.class"), "Order.java"),
                request("com.example.Unknown", null, "Unknown.java"),
                request("com.example.NoSource", classFile("shop/target/classes/com/example/NoSource.class"), null),
                request(
                        "com.example.Missing",
                        classFile("shop/target/classes/com/example/Missing.class"),
                        "Missing.java"));

        SourceLocator.Result result = SourceLocator.resolve(requests);

        assertThat(result.resolved()).isEmpty();
        assertThat(result.notes())
                .containsExactlyInAnyOrder(
                        "3 class(es) were loaded from an archive, so they have no local source path.",
                        "2 class(es) were not compiled into a local Maven or Gradle output directory, so they have no"
                                + " source path.",
                        "1 class(es) recorded no Java or Kotlin source file name, so they have no source path.",
                        "1 class(es) matched no file in their module's source-set or generated-source roots.");
    }

    @Test
    void anExhaustedEntryBudgetKeepsEveryClassOfTheModuleUnresolved() throws IOException {
        for (int i = 0; i < 5; i++) write("shop/src/main/java/com/example/Other" + i + ".java", "package com.example;");
        write("shop/src/main/java/com/example/Order.java", "package com.example; class Order {}");
        URI compiled = classFile("shop/target/classes/com/example/Order.class");

        SourceLocator.Result result = SourceLocator.resolve(
                List.of(request("com.example.Order", compiled, "Order.java")),
                new SourceLocator.Limits(4, 3, 32, 4096, 65536));

        assertThat(result.get("com.example.Order")).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("ran out of budget; 1 class(es)");
    }

    @Test
    void theModuleBudgetAndTheByteBudgetAreBothReported() throws IOException {
        write("one/src/main/java/com/example/A.java", "package com.example; class A {}");
        write("two/src/main/java/com/example/B.java", "package com.example; class B {}");
        List<SourceLocator.Request> requests = List.of(
                request("com.example.A", classFile("one/target/classes/com/example/A.class"), "A.java"),
                request("com.example.B", classFile("two/target/classes/com/example/B.class"), "B.java"));

        SourceLocator.Result modules =
                SourceLocator.resolve(requests, new SourceLocator.Limits(1, 1000, 32, 4096, 65536));
        assertThat(modules.resolved()).containsOnlyKeys("com.example.A");
        assertThat(modules.notes()).singleElement().asString().contains("ran out of budget; 1 class(es)");

        SourceLocator.Result bytes = SourceLocator.resolve(requests, new SourceLocator.Limits(4, 1000, 32, 4096, 10));
        assertThat(bytes.resolved()).isEmpty();
        assertThat(bytes.notes()).singleElement().asString().contains("ran out of budget; 2 class(es)");
    }

    @Test
    void anOversizedOrUnparseableCandidateMakesTheMatchUncertain() throws IOException {
        write("shop/src/main/java/com/example/Order.java", "package com.example; class Order {}");
        write("shop/src/main/java/com/example/nested/Order.java", "package com.example; " + "x".repeat(200));
        URI compiled = classFile("shop/target/classes/com/example/Order.class");

        SourceLocator.Result result = SourceLocator.resolve(
                List.of(request("com.example.Order", compiled, "Order.java")),
                new SourceLocator.Limits(4, 1000, 32, 100, 65536));

        assertThat(result.get("com.example.Order")).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("could not be read or parsed");
    }

    @Test
    void neverFollowsASymbolicLinkInTheSourceTree() throws IOException {
        Path outside = write("outside/com/example/Order.java", "package com.example; class Order {}");
        Files.createDirectories(workspace.resolve("shop/src/main"));
        try {
            Files.createSymbolicLink(
                    workspace.resolve("shop/src/main/java"),
                    outside.getParent().getParent().getParent());
        } catch (UnsupportedOperationException | IOException ex) {
            assumeTrue(false, "Symbolic links are unavailable: " + ex.getMessage());
        }
        URI compiled = classFile("shop/target/classes/com/example/Order.class");

        SourceLocator.Result result =
                SourceLocator.resolve(List.of(request("com.example.Order", compiled, "Order.java")));

        assertThat(result.get("com.example.Order")).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("failed in a module (IOException)");
    }

    @Test
    void countsLinesWithAndWithoutATrailingNewline() {
        assertThat(SourceLocator.lineCount("")).isZero();
        assertThat(SourceLocator.lineCount("a")).isEqualTo(1);
        assertThat(SourceLocator.lineCount("a\n")).isEqualTo(1);
        assertThat(SourceLocator.lineCount("a\nb")).isEqualTo(2);
        assertThat(SourceLocator.lineCount("a\r\nb\r\n")).isEqualTo(2);
    }

    @Test
    void theModuleIsDerivedFromTheOutputDirectoryNotTheWorkingDirectory() {
        assertThat(LocalSourceModule.of(
                        "com.example.Order",
                        Path.of("/r/shop/target/classes/com/example/Order.class")
                                .toUri()))
                .contains(new LocalSourceModule(Path.of("/r/shop"), false, "main"));
        assertThat(LocalSourceModule.of(
                        "com.example.Order",
                        Path.of("/r/svc/build/classes/kotlin/test/com/example/Order.class")
                                .toUri()))
                .contains(new LocalSourceModule(Path.of("/r/svc"), true, "test"));
        assertThat(LocalSourceModule.of(
                        "com.example.Order",
                        Path.of("/r/shop/target/classes/other/Order.class").toUri()))
                .isEmpty();
        assertThat(LocalSourceModule.of("com.example.Order", URI.create("jar:file:/r/a.jar!/com/example/Order.class")))
                .isEmpty();
    }
}
