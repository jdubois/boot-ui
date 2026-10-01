package io.github.jdubois.bootui.engine.architecture;

import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.apiUtil;
import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.compile;
import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import io.github.jdubois.bootui.engine.architecture.generatedfixtures.ApiUtil;
import io.github.jdubois.bootui.engine.architecture.generatedfixtures.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ArchitectureGeneratedCodeRulesTests {
    @TempDir
    Path root;

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void sixteenGeneratedGenericThrowsDisappearButTheHandwrittenOneRemains(ArchitecturePlatform platform)
            throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < 16; i++) {
            sources.put("build/generated/openapi/module" + i + "/ApiUtil.java", apiUtil("sample.module" + i + ".api"));
        }
        sources.put("src/main/java/ApiUtil.java", apiUtil("sample.handwritten.api"));
        var classes = compile(root, "build/classes/java/main", sources);
        AtomicInteger lookups = new AtomicInteger();
        var scanner = new ArchitectureScanner(
                () -> List.of("sample"),
                ignored -> classes,
                platform,
                Clock.systemUTC(),
                List.of(new NoGenericExceptionsRule()),
                imported -> {
                    lookups.incrementAndGet();
                    return ArchitectureGeneratedCode.resolve(imported);
                });
        assertThat(scanner.initialReport().scan().status()).isEqualTo("NOT_SCANNED");
        scanner.lastReport();
        assertThat(lookups).hasValue(0);
        var report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.classesAnalyzed()).isEqualTo(17);
        assertThat(report.evidence().coverageComplete()).isTrue();
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("ARCH-CODE-002");
            assertThat(result.violationCount()).isEqualTo(1);
            assertThat(result.sampleViolations()).singleElement().asString().contains("sample.handwritten.api.ApiUtil");
        });
        assertThat(report.violationDetails().total()).isEqualTo(1);
        var details = scanner.ruleViolations(
                "ARCH-CODE-002", report.violationDetails().scanId(), 0, 100);
        assertThat(details.violations())
                .containsExactlyElementsOf(report.results().get(0).sampleViolations());
        assertThat(scanner.lastReport()).isSameAs(report);
        scanner.applyDismissals(report, Set.of("ARCH-CODE-002"));
        assertThat(lookups).hasValue(1);

        Files.delete(root.resolve("build/generated/openapi/module0/ApiUtil.java"));
        var rescanned = scanner.scan();
        assertThat(rescanned.results())
                .singleElement()
                .satisfies(result -> assertThat(result.violationCount()).isEqualTo(2));
        assertThat(rescanned.violationDetails().total()).isEqualTo(2);
        assertThat(lookups).hasValue(2);
    }

    @Test
    void everyCodingRuleFiltersTargetsBeforeCountingAndPreservesHandwrittenFindings() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String owner : List.of("generated", "authored")) {
            String folder = owner.equals("generated") ? "target/generated-sources/openapi/" : "src/main/java/";
            String packageName = "sample." + owner;
            sources.put(folder + owner + "/ApiUtil.java", """
                    package %s;
                    public class ApiUtil {
                        public static org.slf4j.Logger logger;
                        public static java.util.logging.Logger jul = java.util.logging.Logger.getLogger("example");
                        public static java.util.Date date;
                        public static boolean flag;
                        public static void run() {
                            System.out.println("example");
                            java.util.logging.Logger.getLogger("example").info("example");
                            org.joda.time.LegacyClock.now();
                            new IllegalArgumentException().printStackTrace(new java.io.PrintWriter(System.err));
                            System.exit(1);
                            sun.fixture.InternalApi.call();
                            sample.support.DeprecatedApi.call();
                            org.junit.jupiter.api.Assertions.assertTrue(flag);
                            new Thread(() -> {}).start();
                            assert flag;
                            throw new RuntimeException();
                        }
                    }
                    """.formatted(packageName));
            sources.put(
                    folder + owner + "/BadFailure.java",
                    "package " + packageName + "; public class BadFailure extends RuntimeException {}");
            sources.put(
                    folder + owner + "/FieldInjected.java",
                    "package " + packageName
                            + "; public class FieldInjected { @jakarta.inject.Inject Object injected; }");
        }
        sources.put(
                "target/generated-sources/support/LegacyClock.java",
                "package org.joda.time; public class LegacyClock { public static void now() {} }");
        sources.put(
                "target/generated-sources/support/InternalApi.java",
                "package sun.fixture; public class InternalApi { public static void call() {} }");
        sources.put(
                "target/generated-sources/support/DeprecatedApi.java",
                "package sample.support; @Deprecated public class DeprecatedApi { public static void call() {} }");
        var classes = compile(root, "target/classes", sources);
        var generated = ArchitectureGeneratedCode.resolve(classes);
        assertThat(generated.limitations()).isEmpty();
        assertThat(generated.generatedClasses()).hasSize(6);
        var handwritten = generated.handwrittenClasses(classes);
        var generatedOnly = classes.that(DescribedPredicate.describe(
                "generated fixture", type -> generated.generatedClasses().contains(type.getName())));
        var rules = ArchitectureRuleRegistry.activeRules().stream()
                .filter(rule -> rule.definition().category() == ArchitectureCategory.CODING_PRACTICES)
                .toList();
        assertThat(rules).hasSize(16);
        for (var platform : ArchitecturePlatform.values()) {
            for (var rule : rules) {
                if (platform == ArchitecturePlatform.QUARKUS
                        && rule.definition().id().equals("ARCH-CODE-003")) {
                    // java.util.logging is a built-in Quarkus logging API, so the check does not run there.
                    assertThat(rule.evaluate(new ArchitectureContext(classes, List.of("sample"), platform))
                                    .status())
                            .isEqualTo("SKIPPED");
                    continue;
                }
                var baseline = rule.evaluate(new ArchitectureContext(handwritten, List.of("sample"), platform));
                assertThat(baseline.violationCount())
                        .as("%s positive control", rule.definition().id())
                        .isPositive();
                var context = context(classes, handwritten, platform);
                var mixed = rule.evaluate(context);
                assertThat(mixed).as("%s mixed inputs", rule.definition().id()).isEqualTo(baseline);
                var excluded = context(generatedOnly, generated.handwrittenClasses(generatedOnly), platform);
                var result = rule.evaluate(excluded);
                assertThat(result.status()).as(rule.definition().id()).isEqualTo("PASS");
                assertThat(result.violationCount()).isZero();
                assertThat(result.sampleViolations()).isEmpty();
                assertThat(excluded.evidence().usable()).isFalse();
                assertThat(excluded.evidence().evaluated()).isTrue();
            }
        }
    }

    @Test
    void realKotlinObjectAndCompanionBytecodeUsesGeneratedSourceProvenance() throws IOException {
        String packageName = ApiUtil.class.getPackageName();
        for (Class<?> type : List.of(ApiUtil.class, Container.class, Container.Companion.class)) {
            String resource = type.getName().replace('.', '/') + ".class";
            Path output = root.resolve("build/classes/kotlin/main").resolve(resource);
            Files.createDirectories(output.getParent());
            try (var input = type.getResourceAsStream("/" + resource)) {
                assertThat(input).isNotNull();
                Files.copy(input, output);
            }
        }
        Path source = Path.of("src/test/kotlin")
                .resolve(packageName.replace('.', '/'))
                .resolve("ApiUtil.kt");
        write(root.resolve("build/generated/openapi/unrelated/ApiUtil.kt"), Files.readString(source));
        var classes = new ClassFileImporter().importPath(root.resolve("build/classes/kotlin/main"));
        var raw = new NoGenericExceptionsRule()
                .evaluate(new ArchitectureContext(classes, List.of(packageName), ArchitecturePlatform.SPRING));
        assertThat(raw.violationCount()).isPositive();
        var generated = ArchitectureGeneratedCode.resolve(classes);
        assertThat(generated.limitations()).isEmpty();
        assertThat(generated.generatedClasses())
                .containsExactlyInAnyOrder(
                        ApiUtil.class.getName(), Container.class.getName(), Container.Companion.class.getName());
        var filtered = new NoGenericExceptionsRule()
                .evaluate(context(classes, generated.handwrittenClasses(classes), ArchitecturePlatform.SPRING));
        assertThat(filtered.violationCount()).isZero();
    }

    @Test
    void kotlinFileFacadeCannotBeMistakenForAStaleGeneratedObject() throws IOException {
        Class<?> type = io.github.jdubois.bootui.engine.architecture.facadefixtures.ApiUtil.class;
        String resource = type.getName().replace('.', '/') + ".class";
        Path output = root.resolve("build/classes/kotlin/main").resolve(resource);
        Files.createDirectories(output.getParent());
        try (var input = type.getResourceAsStream("/" + resource)) {
            assertThat(input).isNotNull();
            Files.copy(input, output);
        }
        Path source = Path.of("src/test/kotlin")
                .resolve(type.getPackageName().replace('.', '/'))
                .resolve("ApiUtil.kt");
        write(root.resolve("src/main/kotlin/ApiUtil.kt"), Files.readString(source));
        Path generated = write(
                root.resolve("build/generated/openapi/src/main/kotlin/ApiUtil.kt"),
                "package " + type.getPackageName() + "\nobject ApiUtil { fun run() { throw RuntimeException() } }");
        var classes = new ClassFileImporter().importPath(root.resolve("build/classes/kotlin/main"));
        for (String generatedText : List.of(Files.readString(generated), Files.readString(source))) {
            Files.writeString(generated, generatedText);
            var provenance = ArchitectureGeneratedCode.resolve(classes);
            assertThat(provenance.generatedClasses()).isEmpty();
            var result = new NoGenericExceptionsRule()
                    .evaluate(context(classes, provenance.handwrittenClasses(classes), ArchitecturePlatform.SPRING));
            assertThat(result.violationCount()).isEqualTo(1);
        }
    }

    @Test
    void generatedTypesRemainInTheGraphAndNonCodingRules() throws IOException {
        var classes = compile(
                root,
                "target/classes",
                Map.of(
                        "target/generated-sources/openapi/Service.java",
                        "package sample.service; @org.springframework.stereotype.Service public class Service {"
                                + " sample.web.Controller controller; }",
                        "src/main/java/Controller.java",
                        "package sample.web; @org.springframework.web.bind.annotation.RestController public class Controller {"
                                + " sample.service.Service service; }"));
        var generated = ArchitectureGeneratedCode.resolve(classes);
        assertThat(generated.generatedClasses()).containsExactly("sample.service.Service");
        for (ArchitectureRule rule :
                List.of(new FreeOfPackageCyclesRule(), new ServicesShouldNotDependOnControllersRule())) {
            var original =
                    rule.evaluate(new ArchitectureContext(classes, List.of("sample"), ArchitecturePlatform.SPRING));
            assertThat(original.violationCount()).isPositive();
            assertThat(rule.evaluate(
                            context(classes, generated.handwrittenClasses(classes), ArchitecturePlatform.SPRING)))
                    .isEqualTo(original);
        }
    }

    @Test
    void failedLookupKeepsFindingsAndReportsSanitizedIncompleteEvidence() throws IOException {
        var classes = compile(
                root, "target/classes", Map.of("target/generated-sources/openapi/ApiUtil.java", apiUtil("sample")));
        var scanner = new ArchitectureScanner(
                () -> List.of("sample"),
                ignored -> classes,
                ArchitecturePlatform.SPRING,
                Clock.systemUTC(),
                List.of(new NoGenericExceptionsRule()),
                ignored -> {
                    throw new IllegalStateException("private source at " + root);
                });
        var report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations())
                .singleElement()
                .asString()
                .contains("IllegalStateException")
                .doesNotContain(root.toString(), "private source");
        assertThat(report.results())
                .singleElement()
                .satisfies(result -> assertThat(result.violationCount()).isEqualTo(1));
    }

    private static ArchitectureContext context(JavaClasses classes, JavaClasses coding, ArchitecturePlatform platform) {
        return new ArchitectureContext(
                classes,
                List.of("sample"),
                platform,
                new ArchitectureContext.ArchitectureEvaluationEvidence(),
                new AdvisorViolationCollector(10000),
                coding);
    }
}
