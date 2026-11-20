package io.github.jdubois.bootui.engine.architecture;

import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.cachedJar;
import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.compile;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.engine.archunit.ArchUnitBytecodeImports;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
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

class ArchitectureTestOutputIsolationTests {
    @TempDir
    Path root;

    @Test
    void recognizedTestOutputsDoNotAddFindingsOrApplicationClasses() throws IOException {
        int layout = 0;
        for (String output : List.of("target/test-classes", "build/classes/java/test", "build/classes/kotlin/test")) {
            Path module = root.resolve("layout-" + layout++);
            compile(
                    module,
                    "target/classes",
                    Map.of(
                            "src/main/java/isolated/MainExit.java",
                            "package isolated; public class MainExit { public void run() { System.exit(0); } }"));
            compile(
                    module,
                    output,
                    Map.of(
                            "src/test/java/isolated/TestExit.java",
                            "package isolated; public class TestExit { public void run() { System.exit(0); } }"));
            JavaClasses unfiltered = new ClassFileImporter().importPath(module);
            assertThat(new NoSystemExitRule()
                            .evaluate(new ArchitectureContext(
                                    unfiltered, List.of("isolated"), ArchitecturePlatform.SPRING))
                            .violationCount())
                    .isEqualTo(2);
            for (ArchitecturePlatform platform : ArchitecturePlatform.values()) {
                var report = scan(module, platform, new NoSystemExitRule());
                assertThat(report.classesAnalyzed()).isEqualTo(1);
                assertThat(report.results()).singleElement().satisfies(result -> {
                    assertThat(result.id()).isEqualTo("ARCH-CODE-006");
                    assertThat(result.violationCount()).isEqualTo(1);
                    assertThat(result.sampleViolations())
                            .singleElement()
                            .asString()
                            .contains("MainExit")
                            .doesNotContain("TestExit");
                });
                assertThat(report.evidence().usable()).isTrue();
                assertThat(report.analysisErrors()).isEmpty();
            }
        }
    }

    @Test
    void aTestOnlyImportIsCompleteEmptyButNotUsableApplicationEvidence() throws IOException {
        int layout = 0;
        for (String output : List.of("target/test-classes", "build/classes/java/test", "build/classes/kotlin/test")) {
            Path module = root.resolve("empty-" + layout++);
            compile(
                    module,
                    output,
                    Map.of(
                            "src/test/java/isolated/TestExit.java",
                            "package isolated; public class TestExit { public void run() { System.exit(0); } }"));
            for (ArchitecturePlatform platform : ArchitecturePlatform.values()) {
                var report = scan(module, platform, new NoSystemExitRule());
                assertThat(report.scan().status()).isEqualTo("SCANNED");
                assertThat(report.classesAnalyzed()).isZero();
                assertThat(report.rulesEvaluated()).isZero();
                assertThat(report.results()).isEmpty();
                assertThat(report.evidence().usable()).isFalse();
                assertThat(report.evidence().coverageComplete()).isTrue();
            }
        }
    }

    @Test
    void unknownLayoutsAndArchivesStayEligibleThroughTheDefaultImporter() throws IOException {
        Path module = root.resolve("archive");
        Path output = module.resolve("out/test");
        compile(
                module,
                "out/test",
                Map.of(
                        "src/main/java/isolated/ArchiveExit.java",
                        "package isolated; public class ArchiveExit { public void run() { System.exit(0); } }"));
        assertThat(scan(output, ArchitecturePlatform.SPRING, new NoSystemExitRule())
                        .violationsFound())
                .isEqualTo(1);
        Path jar = module.resolve("target/test-classes/application.jar");
        Files.createDirectories(jar.getParent());
        try (var archive = new JarOutputStream(Files.newOutputStream(jar))) {
            archive.putNextEntry(new JarEntry("isolated/"));
            archive.closeEntry();
            archive.putNextEntry(new JarEntry("isolated/ArchiveExit.class"));
            Files.copy(output.resolve("isolated/ArchiveExit.class"), archive);
            archive.closeEntry();
        }
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (JarFile cached = cachedJar(jar);
                var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            for (ArchitecturePlatform platform : ArchitecturePlatform.values()) {
                var report = new ArchitectureScanner(
                                () -> List.of("isolated"),
                                new ClassFileArchitectureImporter(),
                                platform,
                                Clock.systemUTC(),
                                List.of(new NoSystemExitRule()))
                        .scan();
                assertThat(report.classesAnalyzed()).isEqualTo(1);
                assertThat(report.results())
                        .singleElement()
                        .satisfies(result -> assertThat(result.id()).isEqualTo("ARCH-CODE-006"));
                assertThat(report.analysisErrors()).isEmpty();
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void testOnlyPackageBackEdgesDisappearButRealProductionCyclesRemain() throws IOException {
        for (boolean productionBackEdge : List.of(false, true)) {
            Path module = root.resolve("cycle-" + productionBackEdge);
            compile(
                    module,
                    "target/classes",
                    Map.of(
                            "src/main/java/isolated/first/First.java",
                            "package isolated.first; public class First { public isolated.second.Second field; }",
                            "src/main/java/isolated/second/Second.java",
                            "package isolated.second; public class Second { "
                                    + (productionBackEdge ? "public isolated.first.First field;" : "") + " }"));
            if (!productionBackEdge) {
                compile(
                        module,
                        "target/test-classes",
                        Map.of(
                                "src/test/java/isolated/second/TestEdge.java",
                                "package isolated.second; public class TestEdge { public isolated.first.First field; }"),
                        module.resolve("target/classes").toString());
            }
            JavaClasses all = new ClassFileImporter().importPath(module);
            assertThat(new FreeOfPackageCyclesRule()
                            .evaluate(new ArchitectureContext(all, List.of("isolated"), ArchitecturePlatform.SPRING))
                            .violationCount())
                    .isPositive();
            var report = scan(module, ArchitecturePlatform.SPRING, new FreeOfPackageCyclesRule());
            assertThat(report.classesAnalyzed()).isEqualTo(2);
            assertThat(report.results()).hasSize(productionBackEdge ? 1 : 0);
            assertThat(report.evidence().usable()).isTrue();
        }
    }

    @Test
    void excludedTestSubtypesDoNotHideFindingsOrEraseProductionDescendants() throws IOException {
        Map<String, String> fixtures = Map.of(
                "Utility",
                "package isolated; public class Utility { public static int value() { return 1; } }",
                "Task",
                "package isolated; public class Task {"
                        + " @org.springframework.scheduling.annotation.Scheduled(fixedRate=1000)"
                        + " public void tick() {} }");
        for (String name : List.of("Utility", "Task")) {
            ArchitectureRule rule = name.equals("Utility")
                    ? new UtilityClassesShouldBeFinalWithPrivateConstructorRule()
                    : new ScheduledAndEventListenerMethodsShouldBeOnManagedBeansRule();
            for (String kind : List.of("test", "production", "transitive", "unknown")) {
                Path module = root.resolve(name + "-" + kind);
                Path main = module.resolve("target/classes");
                compile(
                        module,
                        "target/classes",
                        Map.of("src/main/java/isolated/" + name + ".java", fixtures.get(name)));
                String output = kind.equals("production")
                        ? "target/classes"
                        : kind.equals("unknown") ? "out/test" : "target/test-classes";
                compile(
                        module,
                        output,
                        Map.of(
                                "src/" + (kind.equals("production") ? "main" : "test") + "/java/isolated/Child.java",
                                "package isolated; public class Child extends " + name + " {}"),
                        main.toString());
                if (kind.equals("transitive")) {
                    compile(
                            module,
                            "target/classes",
                            Map.of(
                                    "src/main/java/isolated/Grandchild.java",
                                    "package isolated; public class Grandchild extends Child {}"),
                            main + java.io.File.pathSeparator + module.resolve(output));
                }
                var report = scan(module, ArchitecturePlatform.SPRING, rule);
                assertThat(report.classesAnalyzed()).as(name + kind).isEqualTo(kind.equals("test") ? 1 : 2);
                int expected = kind.equals("test") ? (name.equals("Utility") ? 2 : 1) : 0;
                assertThat(report.results().stream()
                                .mapToInt(result -> result.violationCount())
                                .sum())
                        .as(name + kind)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void testFieldWritesDoNotInvalidateCapturedProxyEvidenceButProductionAndUnknownWritesDo() throws IOException {
        for (String output : List.of("target/test-classes", "target/classes", "out/test")) {
            Path module = root.resolve("proxy-" + output.replace('/', '-'));
            Path main = module.resolve("target/classes");
            compile(
                    module,
                    "target/classes",
                    Map.of(
                            "src/main/java/isolated/ProxyBean.java",
                            "package isolated; @org.springframework.stereotype.Service public class ProxyBean {"
                                    + " @org.springframework.beans.factory.annotation.Autowired"
                                    + " @org.springframework.context.annotation.Lazy ProxyBean self;"
                                    + " public void caller() { self.callee(); }"
                                    + " @org.springframework.transaction.annotation.Transactional("
                                    + " propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)"
                                    + " public void callee() {} }"));
            compile(
                    module,
                    output,
                    Map.of(
                            "src/test/java/isolated/Writer.java",
                            "package isolated; public class Writer { public void replace(ProxyBean bean) { bean.self=bean; } }"),
                    main.toString());
            var imported = ArchUnitBytecodeImports.importPath(module);
            assertThat(imported.bytecode().bytesOf(imported.classes().get("isolated.ProxyBean")))
                    .isPresent();
            ArchitectureClassImporter importer = new ArchitectureClassImporter() {
                @Override
                public JavaClasses importPackages(List<String> packages) {
                    return imported.classes();
                }

                @Override
                public ArchUnitBytecodeImports.Result importWithBytecode(List<String> packages) {
                    return imported;
                }
            };
            var report = new ArchitectureScanner(
                            () -> List.of("isolated"),
                            importer,
                            ArchitecturePlatform.SPRING,
                            Clock.systemUTC(),
                            List.of(new NoSelfInvocationOfProxiedMethodsRule()))
                    .scan();
            boolean knownTest = output.equals("target/test-classes");
            assertThat(report.classesAnalyzed()).isEqualTo(knownTest ? 1 : 2);
            assertThat(report.results()).hasSize(knownTest ? 0 : 1);
            assertThat(report.evidence().usable()).isTrue();
            assertThat(report.evidence().coverageComplete()).isTrue();
        }
    }

    @Test
    void formatterSharingComesFromProductionBeansNotExcludedTestBeans() throws IOException {
        for (String kind : List.of("test", "production", "unknown", "transitive")) {
            Path module = root.resolve("formatter-" + kind);
            Path main = module.resolve("target/classes");
            compile(
                    module,
                    "target/classes",
                    Map.of(
                            "src/main/java/isolated/Holder.java",
                            "package isolated; public class Holder {"
                                    + " private final java.text.SimpleDateFormat value=new java.text.SimpleDateFormat(\"yyyy\"); }"));
            String output = kind.equals("production")
                    ? "target/classes"
                    : kind.equals("unknown") ? "out/test" : "target/test-classes";
            compile(
                    module,
                    output,
                    Map.of(
                            "src/test/java/isolated/Child.java",
                            "package isolated; "
                                    + (kind.equals("transitive") ? "" : "@org.springframework.stereotype.Component ")
                                    + "public class Child extends Holder {}"),
                    main.toString());
            if (kind.equals("transitive")) {
                compile(
                        module,
                        "target/classes",
                        Map.of(
                                "src/main/java/isolated/Grandchild.java",
                                "package isolated; @org.springframework.stereotype.Component public class Grandchild extends Child {}"),
                        main + java.io.File.pathSeparator + module.resolve(output));
            }
            var report = scan(module, ArchitecturePlatform.SPRING, new NoSharedStatefulJdkFormattersAndParsersRule());
            assertThat(report.classesAnalyzed()).isEqualTo(kind.equals("test") ? 1 : 2);
            assertThat(report.results()).hasSize(kind.equals("test") ? 0 : 1);
        }
    }

    @Test
    void realProductionDependenciesOnInternalTestTargetsAreNotWaived() throws IOException {
        Path module = root.resolve("module");
        compile(
                module,
                "target/test-classes",
                Map.of(
                        "src/test/java/isolated/two/internal/SecretFixture.java",
                        "package isolated.two.internal; public class SecretFixture {}"));
        compile(
                module,
                "target/classes",
                Map.of(
                        "src/main/java/isolated/one/Client.java",
                        "package isolated.one; public class Client { isolated.two.internal.SecretFixture field; }"),
                module.resolve("target/test-classes").toString());
        var report = scan(module, ArchitecturePlatform.SPRING, new InternalPackagesShouldNotBeAccessedExternallyRule());
        assertThat(report.classesAnalyzed()).isEqualTo(1);
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("ARCH-MOD-001");
            assertThat(result.violationCount()).isPositive();
            assertThat(result.sampleViolations())
                    .anySatisfy(text -> assertThat(text).contains("two.internal"));
        });
    }

    private static ArchitectureReport scan(Path output, ArchitecturePlatform platform, ArchitectureRule... rules) {
        return new ArchitectureScanner(
                        () -> List.of("isolated"),
                        CompiledOutputFixtures.importer(output),
                        platform,
                        Clock.systemUTC(),
                        List.of(rules))
                .scan();
    }
}
