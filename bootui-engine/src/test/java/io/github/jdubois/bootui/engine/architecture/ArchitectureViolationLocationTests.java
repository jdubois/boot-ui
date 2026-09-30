package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorLocations;
import io.github.jdubois.bootui.engine.architecture.cyclefixtures.alpha.Alpha;
import io.github.jdubois.bootui.engine.architecture.cyclefixtures.beta.Beta;
import io.github.jdubois.bootui.engine.architecture.locationfixtures.LocatedStreamUser;
import io.github.jdubois.bootui.engine.architecture.pagingfixtures.AdvisorViolationFixture;
import io.github.jdubois.bootui.engine.archunit.ArchUnitSourceLocations;
import io.github.jdubois.bootui.engine.source.SourceLocator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Structured violation locations on the Architecture advisor: taken from ArchUnit's violating objects, completed
 * with a resolved local source path and a verified line during the explicit scan, and kept aligned with the
 * unchanged violation text on every sample and detail page.
 */
class ArchitectureViolationLocationTests {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);
    private static final String JAVA_FIXTURES = LocatedStreamUser.class.getPackageName();
    private static final String KOTLIN_FIXTURES = "io.github.jdubois.bootui.engine.architecture.kotlinlocationfixtures";

    private static ArchitectureScanner scanner(JavaClasses classes, String root, List<ArchitectureRule> rules) {
        return new ArchitectureScanner(
                () -> List.of(root), packages -> classes, ArchitecturePlatform.SPRING, CLOCK, rules);
    }

    private static ArchitectureRuleResultDto result(ArchitectureReport report, String id) {
        return report.results().stream()
                .filter(result -> id.equals(result.id()))
                .findFirst()
                .orElseThrow();
    }

    private static AdvisorViolationLocationDto locationOf(ArchitectureRuleResultDto result, String fragment) {
        for (int index = 0; index < result.sampleViolations().size(); index++) {
            if (result.sampleViolations().get(index).contains(fragment)) {
                return result.sampleLocations().get(index);
            }
        }
        throw new AssertionError("No sample contains " + fragment + ": " + result.sampleViolations());
    }

    private static List<AdvisorRuleViolationsDto> pages(
            ArchitectureScanner scanner, String id, String scanId, int size) {
        List<AdvisorRuleViolationsDto> pages = new ArrayList<>();
        int offset = 0;
        while (true) {
            AdvisorRuleViolationsDto page = scanner.ruleViolations(id, scanId, offset, size);
            pages.add(page);
            if (!page.page().hasMore()) return pages;
            offset += page.page().returned();
        }
    }

    @Test
    void javaFindingsCarryTheirClassMemberSourceFileLineAndLocalPath() throws Exception {
        JavaClasses classes = new ClassFileImporter().importClasses(LocatedStreamUser.class);
        ArchitectureRule rule = new NoStandardStreamsRule();
        ArchitectureReport report =
                scanner(classes, JAVA_FIXTURES, List.of(rule)).scan();
        ArchitectureRuleResultDto result = result(report, "ARCH-CODE-001");

        assertThat(result.sampleLocations()).hasSameSizeAs(result.sampleViolations());
        String className = LocatedStreamUser.class.getName();

        AdvisorViolationLocationDto method = locationOf(result, "emit()");
        assertThat(method.className()).isEqualTo(className);
        assertThat(method.memberName()).isEqualTo("emit");
        assertThat(method.kind()).isEqualTo(AdvisorViolationLocationDto.METHOD);
        assertThat(method.sourceFile()).isEqualTo("LocatedStreamUser.java");
        assertThat(method.line()).isEqualTo(16);
        assertThat(method.precision()).isEqualTo(AdvisorViolationLocationDto.PRECISION_LINE);
        Path path = Path.of(method.sourcePath());
        assertThat(path).isAbsolute();
        assertThat(path.toString().replace('\\', '/'))
                .endsWith("bootui-engine/src/test/java/io/github/jdubois/bootui/engine/architecture/"
                        + "locationfixtures/LocatedStreamUser.java");
        assertThat(Files.readAllLines(path).get(15)).contains("System.out.println(\"method body\")");

        // ArchUnit attributes the field initializer's lambda body to the constructor that creates it.
        AdvisorViolationLocationDto lambda = locationOf(result, "<init>()");
        assertThat(lambda.memberName()).isEqualTo("<init>");
        assertThat(lambda.kind()).isEqualTo(AdvisorViolationLocationDto.CONSTRUCTOR);
        assertThat(lambda.line()).isEqualTo(13);
        assertThat(lambda.precision()).isEqualTo(AdvisorViolationLocationDto.PRECISION_LINE);

        AdvisorViolationLocationDto initializer = locationOf(result, "<clinit>");
        assertThat(initializer.memberName()).isNull();
        assertThat(initializer.line()).isEqualTo(10);
        assertThat(report.violationDetails().locationNotes()).isEmpty();
    }

    @Test
    void violationTextCountsAndOrderAreExactlyWhatArchUnitReports() {
        JavaClasses classes = new ClassFileImporter().importClasses(AdvisorViolationFixture.class);
        List<ArchitectureRule> rules =
                List.of(new NoSelfInvocationOfProxiedMethodsRule(), new NoGenericExceptionsRule());
        ArchitectureScanner scanner = scanner(classes, AdvisorViolationFixture.class.getPackageName(), rules);
        ArchitectureReport report = scanner.scan();
        for (ArchitectureRule rule : rules) {
            String id = rule.definition().id();
            ArchitectureRuleResultDto summary = result(report, id);
            List<String> raw = ((AbstractArchitectureRule) rule)
                    .rule(new ArchitectureContext(
                            classes,
                            List.of(AdvisorViolationFixture.class.getPackageName()),
                            ArchitecturePlatform.SPRING))
                    .allowEmptyShould(true)
                    .evaluate(classes)
                    .getFailureReport()
                    .getDetails();
            List<String> expected =
                    raw.stream().map(ArchitectureRuleSupport::detail).toList();
            assertThat(summary.violationCount()).isEqualTo(expected.size());
            assertThat(summary.sampleViolations()).containsExactlyElementsOf(expected.subList(0, 10));
            assertThat(summary.sampleLocations()).hasSize(10).doesNotContainNull();

            List<String> texts = new ArrayList<>();
            List<AdvisorViolationLocationDto> locations = new ArrayList<>();
            for (AdvisorRuleViolationsDto page :
                    pages(scanner, id, report.violationDetails().scanId(), 7)) {
                assertThat(page.locations()).hasSameSizeAs(page.violations());
                texts.addAll(page.violations());
                locations.addAll(page.locations());
            }
            assertThat(texts).containsExactlyElementsOf(expected);
            assertThat(locations.subList(0, 10)).containsExactlyElementsOf(summary.sampleLocations());
            for (int index = 0; index < texts.size(); index++) {
                AdvisorViolationLocationDto location = locations.get(index);
                assertThat(location.className()).isEqualTo(AdvisorViolationFixture.class.getName());
                // Each location is the element of its own line: the line ArchUnit printed into that very text.
                assertThat(location.line()).isNotNull();
                assertThat(raw.get(index)).endsWith("(AdvisorViolationFixture.java:" + location.line() + ")");
            }
        }
    }

    @Test
    void dismissedRulesAndTruncatedRetentionKeepEveryListAligned() {
        JavaClasses classes = new ClassFileImporter().importClasses(AdvisorViolationFixture.class);
        ArchitectureScanner scanner = scanner(
                classes, AdvisorViolationFixture.class.getPackageName(), List.of(new NoGenericExceptionsRule()));
        scanner.setViolationRetentionLimit(() -> 5);
        ArchitectureReport report = scanner.scan();
        assertThat(report.violationDetails().truncated()).isTrue();

        ArchitectureReport dismissed = scanner.applyDismissals(report, Set.of("ARCH-CODE-002"));
        ArchitectureRuleResultDto rule = result(dismissed, "ARCH-CODE-002");
        assertThat(rule.dismissed()).isTrue();
        assertThat(rule.sampleLocations())
                .containsExactlyElementsOf(result(report, "ARCH-CODE-002").sampleLocations());
        assertThat(rule.sampleLocations()).hasSameSizeAs(rule.sampleViolations());

        List<AdvisorRuleViolationsDto> pages =
                pages(scanner, "ARCH-CODE-002", dismissed.violationDetails().scanId(), 2);
        assertThat(pages).allSatisfy(page -> assertThat(page.locations()).hasSameSizeAs(page.violations()));
        List<AdvisorViolationLocationDto> retained =
                pages.stream().flatMap(page -> page.locations().stream()).toList();
        assertThat(retained)
                .hasSize(5)
                .containsExactlyElementsOf(rule.sampleLocations().subList(0, 5));
    }

    @Test
    void kotlinLocationsNameTheDeclaringFileAndNeverShowAnInlinedLine() throws Exception {
        JavaClasses classes = new ClassFileImporter().importPackages(KOTLIN_FIXTURES);
        ArchitectureReport report = scanner(classes, KOTLIN_FIXTURES, List.of(new NoStandardStreamsRule()))
                .scan();
        ArchitectureRuleResultDto result = result(report, "ARCH-CODE-001");
        assertThat(result.sampleLocations()).hasSameSizeAs(result.sampleViolations());

        AdvisorViolationLocationDto direct = locationOf(result, "KotlinStreamUser.direct()");
        assertThat(direct.memberName()).isEqualTo("direct");
        assertThat(direct.sourceFile()).isEqualTo("KotlinLocationFixtures.kt");
        assertThat(direct.line()).isEqualTo(7);
        assertThat(direct.precision()).isEqualTo(AdvisorViolationLocationDto.PRECISION_LINE);
        Path source = Path.of(direct.sourcePath());
        assertThat(source.getFileName()).hasToString("KotlinLocationFixtures.kt");
        assertThat(Files.readAllLines(source).get(6)).contains("System.err.println");

        // The inlined body carries a line of KotlinInlineHelpers.kt; the text keeps it, the location drops it.
        String inlinedText = result.sampleViolations().stream()
                .filter(text -> text.contains("KotlinStreamUser.inlined()"))
                .findFirst()
                .orElseThrow();
        assertThat(inlinedText).contains("KotlinLocationFixtures.kt:25");
        AdvisorViolationLocationDto inlined = locationOf(result, "KotlinStreamUser.inlined()");
        assertThat(inlined.memberName()).isEqualTo("inlined");
        assertThat(inlined.line()).isNull();
        assertThat(inlined.precision()).isEqualTo(AdvisorViolationLocationDto.PRECISION_MEMBER);
        assertThat(inlined.sourcePath()).isEqualTo(direct.sourcePath());

        AdvisorViolationLocationDto companion = locationOf(result, "Companion.fromCompanion()");
        assertThat(companion.className()).endsWith("KotlinStreamUser$Companion");
        assertThat(companion.sourceFile()).isEqualTo("KotlinLocationFixtures.kt");
        assertThat(companion.line()).isEqualTo(16);
        assertThat(companion.sourcePath()).isEqualTo(direct.sourcePath());

        AdvisorViolationLocationDto facade = locationOf(result, "KotlinLocationFixturesKt.topLevel()");
        assertThat(facade.className()).endsWith("KotlinLocationFixturesKt");
        assertThat(facade.memberName()).isEqualTo("topLevel");
        assertThat(facade.sourceFile()).isEqualTo("KotlinLocationFixtures.kt");
        assertThat(facade.line()).isEqualTo(22);
        assertThat(facade.sourcePath()).isEqualTo(direct.sourcePath());

        AdvisorViolationLocationDto helper = locationOf(result, "KotlinInlineHelpersKt.shout(");
        assertThat(helper.sourceFile()).isEqualTo("KotlinInlineHelpers.kt");
        assertThat(helper.line()).isEqualTo(6);
        assertThat(Path.of(helper.sourcePath()).getFileName()).hasToString("KotlinInlineHelpers.kt");
    }

    @Test
    void findingsThatSpanSeveralElementsCarryNoLocation() {
        JavaClasses classes = new ClassFileImporter().importClasses(Alpha.class, Beta.class);
        String root = "io.github.jdubois.bootui.engine.architecture.cyclefixtures";
        ArchitectureScanner scanner = scanner(classes, root, List.of(new FreeOfPackageCyclesRule()));
        ArchitectureReport report = scanner.scan();
        ArchitectureRuleResultDto cycles = result(report, "ARCH-PKG-001");
        assertThat(cycles.sampleViolations()).isNotEmpty();
        assertThat(cycles.sampleLocations()).isEmpty();
        AdvisorRuleViolationsDto page =
                scanner.ruleViolations("ARCH-PKG-001", report.violationDetails().scanId(), 0, 100);
        assertThat(page.violations()).isNotEmpty();
        assertThat(page.locations()).isEmpty();
    }

    @Test
    void anExhaustedLookupBudgetKeepsNoPathAndSaysWhy() {
        JavaClasses classes = new ClassFileImporter().importClasses(LocatedStreamUser.class);
        AtomicInteger resolutions = new AtomicInteger();
        BiFunction<JavaClasses, java.util.Collection<AdvisorViolationLocationDto>, AdvisorLocations.Resolution> tiny =
                (imported, located) -> {
                    resolutions.incrementAndGet();
                    return ArchUnitSourceLocations.resolve(imported, located, new SourceLocator.Limits(1, 1, 1, 1, 1));
                };
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> List.of(JAVA_FIXTURES),
                packages -> classes,
                ArchitecturePlatform.SPRING,
                CLOCK,
                List.of(new NoStandardStreamsRule()),
                ArchitectureGeneratedCode::resolve,
                tiny);
        ArchitectureReport report = scanner.scan();
        ArchitectureRuleResultDto result = result(report, "ARCH-CODE-001");
        assertThat(resolutions).hasValue(1);
        assertThat(result.sampleLocations()).isNotEmpty().allSatisfy(location -> {
            assertThat(location.sourcePath()).isNull();
            assertThat(location.sourceFile()).isEqualTo("LocatedStreamUser.java");
        });
        assertThat(report.violationDetails().locationNotes())
                .singleElement()
                .asString()
                .contains("ran out of budget");

        // Detail reads serve the published records: they never resolve again.
        scanner.ruleViolations("ARCH-CODE-001", report.violationDetails().scanId(), 0, 100);
        assertThat(resolutions).hasValue(1);
    }

    @Test
    void aFailedLookupDropsUnverifiableLinesInsteadOfShowingThem() {
        JavaClasses classes = new ClassFileImporter().importClasses(LocatedStreamUser.class);
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> List.of(JAVA_FIXTURES),
                packages -> classes,
                ArchitecturePlatform.SPRING,
                CLOCK,
                List.of(new NoStandardStreamsRule()),
                ArchitectureGeneratedCode::resolve,
                (imported, located) -> {
                    throw new IllegalStateException("boom");
                });
        ArchitectureReport report = scanner.scan();
        ArchitectureRuleResultDto result = result(report, "ARCH-CODE-001");
        assertThat(result.sampleViolations()).hasSize(3);
        assertThat(result.sampleLocations()).hasSize(3).allSatisfy(location -> {
            assertThat(location.line()).isNull();
            assertThat(location.sourcePath()).isNull();
        });
        assertThat(report.violationDetails().locationNotes())
                .singleElement()
                .asString()
                .contains("Source lookup failed (IllegalStateException)");
    }
}
