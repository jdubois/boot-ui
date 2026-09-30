package io.github.jdubois.bootui.autoconfigure.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the Jackson 3 JSON of a violation location, byte for byte the same as Quarkus' Jackson 2 output, and proves a
 * default page of the largest possible located violations stays well within the default MCP response budget.
 */
class AdvisorViolationLocationJsonTests {

    static final String LOCATION_JSON = "{\"className\":\"com.example.OrderService\",\"memberName\":\"place\","
            + "\"kind\":\"METHOD\",\"sourceFile\":\"OrderService.java\",\"line\":42,"
            + "\"sourcePath\":\"/work/shop/src/main/java/com/example/OrderService.java\",\"precision\":\"LINE\"}";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void aLocationSerializesItsComponentsInDeclarationOrder() {
        AdvisorViolationLocationDto location = new AdvisorViolationLocationDto(
                "com.example.OrderService",
                "place",
                "METHOD",
                "OrderService.java",
                42,
                "/work/shop/src/main/java/com/example/OrderService.java");

        assertThat(mapper.writeValueAsString(location)).isEqualTo(LOCATION_JSON);
        assertThat(mapper.writeValueAsString(
                        new AdvisorViolationLocationDto("com.example.Order", null, null, null, null, null)))
                .isEqualTo("{\"className\":\"com.example.Order\",\"memberName\":null,\"kind\":\"CLASS\","
                        + "\"sourceFile\":null,\"line\":null,\"sourcePath\":null,\"precision\":\"CLASS\"}");
    }

    @Test
    void aDefaultPageOfMaximalLocatedViolationsStaysWithinTheDefaultMcpBudget() {
        String text = "x".repeat(240);
        List<String> violations = new ArrayList<>();
        List<AdvisorViolationLocationDto> locations = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            violations.add(text);
            locations.add(new AdvisorViolationLocationDto(
                    "c".repeat(AdvisorViolationLocationDto.MAX_NAME_LENGTH),
                    "m".repeat(AdvisorViolationLocationDto.MAX_NAME_LENGTH),
                    "METHOD",
                    "f".repeat(AdvisorViolationLocationDto.MAX_SOURCE_FILE_LENGTH),
                    Integer.MAX_VALUE,
                    "/" + "p".repeat(AdvisorViolationLocationDto.MAX_PATH_LENGTH - 1)));
        }
        AdvisorRuleViolationsDto page = new AdvisorRuleViolationsDto(
                "scan", "RULE", 100, 100, false, violations, new PageMetadata(100, 100, 0, 100, 100, false), locations);

        int bytes = mapper.writeValueAsBytes(page).length;

        assertThat(page.locations()).hasSize(100).doesNotContainNull();
        assertThat(bytes).isLessThan(new BootUiProperties().getMcp().getMaxResponseBytes() / 8);
    }
}
