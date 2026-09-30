package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * REST API findings about one handler method, exception handler, or controller carry that element's location from
 * the same ArchUnit model the rules read; route-level findings that name several handlers carry none.
 */
class RestApiViolationLocationTests {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private static RestApiScanner scanner(String basePackage) {
        return new RestApiScanner(
                () -> List.of(basePackage), new ClassFileRestApiImporter(), () -> true, () -> false, CLOCK);
    }

    private static List<AdvisorRuleViolationsDto> pages(RestApiScanner scanner, String id, String scanId) {
        List<AdvisorRuleViolationsDto> pages = new ArrayList<>();
        int offset = 0;
        while (true) {
            AdvisorRuleViolationsDto page = scanner.ruleViolations(id, scanId, offset, 3);
            pages.add(page);
            if (!page.page().hasMore()) return pages;
            offset += page.page().returned();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "io.github.jdubois.bootui.engine.restapi.fixtures.bad, BadOrderController, BadOrderController.java",
        "io.github.jdubois.bootui.engine.restapi.jaxrs.bad, BadGadgetResource, BadGadgetResource.java",
        "io.github.jdubois.bootui.engine.restapi.kotlinfixtures, KotlinOrderController, KotlinRestApiFixtures.kt"
    })
    void handlerFindingsPointAtTheirHandlerMethodOnEveryStack(String basePackage, String controller, String file)
            throws Exception {
        RestApiScanner scanner = scanner(basePackage);
        RestApiReport report = scanner.scan();
        String scanId = report.violationDetails().scanId();
        int located = 0;
        for (RestApiRuleResultDto result : report.results()) {
            assertThat(result.sampleLocations())
                    .satisfiesAnyOf(
                            locations -> assertThat(locations).isEmpty(),
                            locations -> assertThat(locations).hasSameSizeAs(result.sampleViolations()));
            List<String> texts = new ArrayList<>();
            List<AdvisorViolationLocationDto> locations = new ArrayList<>();
            for (AdvisorRuleViolationsDto page : pages(scanner, result.id(), scanId)) {
                assertThat(page.locations())
                        .satisfiesAnyOf(
                                list -> assertThat(list).isEmpty(),
                                list -> assertThat(list).hasSameSizeAs(page.violations()));
                texts.addAll(page.violations());
                locations.addAll(
                        page.locations().isEmpty()
                                ? java.util.Collections.nCopies(
                                        page.violations().size(), null)
                                : page.locations());
            }
            assertThat(texts.subList(0, result.sampleViolations().size()))
                    .containsExactlyElementsOf(result.sampleViolations());
            for (int index = 0; index < texts.size(); index++) {
                AdvisorViolationLocationDto location = locations.get(index);
                String text = texts.get(index);
                if (location == null) continue;
                located++;
                assertThat(location.sourceFile()).isNotBlank();
                assertThat(Files.isRegularFile(Path.of(location.sourcePath()))).isTrue();
                if (location.memberName() != null) {
                    String simple =
                            location.className().substring(location.className().lastIndexOf('.') + 1);
                    assertThat(text).startsWith(simple + "#" + location.memberName());
                    assertThat(location.kind()).isEqualTo(AdvisorViolationLocationDto.METHOD);
                } else {
                    assertThat(location.precision()).isEqualTo(AdvisorViolationLocationDto.PRECISION_CLASS);
                    assertThat(text)
                            .startsWith(location.className()
                                    .substring(location.className().lastIndexOf('.') + 1));
                }
            }
        }
        assertThat(located).isPositive();
        AdvisorViolationLocationDto handler = report.results().stream()
                .flatMap(result -> result.sampleLocations().stream())
                .filter(location -> location != null && location.className().endsWith("." + controller))
                .filter(location -> location.memberName() != null)
                .findFirst()
                .orElseThrow();
        assertThat(handler.sourceFile()).isEqualTo(file);
        assertThat(Path.of(handler.sourcePath()).getFileName()).hasToString(file);
        if (handler.line() != null) {
            assertThat(handler.line())
                    .isLessThanOrEqualTo(
                            Files.readAllLines(Path.of(handler.sourcePath())).size());
        }
        assertThat(report.violationDetails().locationNotes()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"io.github.jdubois.bootui.engine.restapi.fixtures.bad"})
    void dismissalKeepsTextAndLocationsTogether(String basePackage) {
        RestApiScanner scanner = scanner(basePackage);
        RestApiReport report = scanner.scan();
        RestApiRuleResultDto located = report.results().stream()
                .filter(result -> !result.sampleLocations().isEmpty())
                .findFirst()
                .orElseThrow();
        RestApiReport dismissed = scanner.applyDismissals(report, Set.of(located.id()));
        RestApiRuleResultDto after = dismissed.results().stream()
                .filter(result -> result.id().equals(located.id()))
                .findFirst()
                .orElseThrow();
        assertThat(after.dismissed()).isTrue();
        assertThat(after.sampleViolations()).containsExactlyElementsOf(located.sampleViolations());
        assertThat(after.sampleLocations()).containsExactlyElementsOf(located.sampleLocations());
    }
}
