package io.github.jdubois.bootui.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdvisorViolationLocationDtoTests {

    private static final AdvisorViolationLocationDto LINE = new AdvisorViolationLocationDto(
            "com.example.OrderService", "place", "METHOD", "OrderService.java", 42, "/work/OrderService.java");

    @Test
    void precisionFollowsWhatTheLocationActuallyKnows() {
        assertThat(LINE.precision()).isEqualTo("LINE");
        assertThat(LINE.withoutLine().precision()).isEqualTo("MEMBER");
        assertThat(LINE.withoutLine().line()).isNull();
        AdvisorViolationLocationDto type =
                new AdvisorViolationLocationDto("com.example.Order", null, "METHOD", "Order.java", null, null);
        assertThat(type.precision()).isEqualTo("CLASS");
        assertThat(type.kind()).isEqualTo("CLASS");
        assertThat(new AdvisorViolationLocationDto("com.example.Order", null, null, null, 7, null, "CLASS").precision())
                .isEqualTo("LINE");
    }

    @Test
    void discardsUnknownLinesAndUnknownKinds() {
        assertThat(new AdvisorViolationLocationDto("a.B", "m", "METHOD", "B.java", 0, null).line())
                .isNull();
        assertThat(new AdvisorViolationLocationDto("a.B", "m", "METHOD", "B.java", -3, null).line())
                .isNull();
        AdvisorViolationLocationDto unknownKind =
                new AdvisorViolationLocationDto("a.B", "m", "LAMBDA", null, null, null);
        assertThat(unknownKind.memberName()).isNull();
        assertThat(unknownKind.kind()).isEqualTo("CLASS");
    }

    @Test
    void boundsEveryStringAndPathInsteadOfTruncatingThem() {
        String longName = "a".repeat(AdvisorViolationLocationDto.MAX_NAME_LENGTH + 1);
        String longPath = "/" + "p".repeat(AdvisorViolationLocationDto.MAX_PATH_LENGTH);
        AdvisorViolationLocationDto bounded =
                new AdvisorViolationLocationDto("a.B", longName, "FIELD", "dir/B.java", 3, longPath);
        assertThat(bounded.memberName()).isNull();
        assertThat(bounded.sourceFile()).isNull();
        assertThat(bounded.sourcePath()).isNull();
        assertThat(new AdvisorViolationLocationDto("a.B", "m\u001b[2J", "METHOD", "B\u0007.java", 3, "/w\u0000/B.java"))
                .satisfies(location -> {
                    assertThat(location.memberName()).isNull();
                    assertThat(location.sourceFile()).isNull();
                    assertThat(location.sourcePath()).isNull();
                });
        assertThatThrownBy(() -> new AdvisorViolationLocationDto(longName, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdvisorViolationLocationDto(" ", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locationListsAreEitherEmptyOrAlignedWithTheirTexts() {
        List<AdvisorViolationLocationDto> locations = new ArrayList<>(Arrays.asList(LINE, null));
        AdvisorRuleViolationsDto page =
                new AdvisorRuleViolationsDto("scan", "rule", 2, 2, false, List.of("first", "second"), null, locations);
        locations.clear();
        assertThat(page.locations()).containsExactly(LINE, null);

        assertThat(new AdvisorRuleViolationsDto(
                                "scan",
                                "rule",
                                2,
                                2,
                                false,
                                List.of("first", "second"),
                                null,
                                Arrays.asList(null, null))
                        .locations())
                .isEmpty();
        assertThatThrownBy(() -> new AdvisorRuleViolationsDto(
                        "scan", "rule", 2, 2, false, List.of("first", "second"), null, List.of(LINE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchitectureRuleResultDto(
                                "id", "n", "c", "HIGH", "d", "VIOLATION", 1, List.of("a"), "r", "u")
                        .withSampleLocations(List.of(LINE, LINE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void olderConstructorsKeepTheirContentAndCarryNoLocations() {
        RestApiRuleResultDto rest =
                new RestApiRuleResultDto("id", "n", "c", "HIGH", "d", "VIOLATION", 1, List.of("a"), "r", "u", true);
        assertThat(rest.sampleLocations()).isEmpty();
        assertThat(rest.withSampleLocations(List.of(LINE)).withDismissed(false).sampleLocations())
                .containsExactly(LINE);
        HibernateRuleResultDto hibernate = new HibernateRuleResultDto(
                        "id", "n", "c", "HIGH", "d", "VIOLATION", 1, List.of("a"), "r", "u", false, "note")
                .withSampleLocations(List.of(LINE));
        assertThat(hibernate.withCoverageNote("other").withDismissed(true).sampleLocations())
                .containsExactly(LINE);
        assertThat(new AdvisorRuleViolationsDto("scan", "rule", 1, 1, false, List.of("a"), null).locations())
                .isEmpty();
        assertThat(new AdvisorViolationDetailsDto("scan", 1, 1, 10, false).locationNotes())
                .isEmpty();
        assertThat(new AdvisorViolationDetailsDto(
                                "scan", 1, 1, 10, false, Arrays.asList(" note\n", null, "note", "", "other"))
                        .locationNotes())
                .containsExactly("note", "other");
    }
}
