package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Jackson 2 must emit violation locations exactly as Spring's Jackson 3 does. */
class AdvisorViolationLocationSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aLocationAndAPageSerializeLikeSpring() throws Exception {
        AdvisorViolationLocationDto location = new AdvisorViolationLocationDto(
                "com.example.OrderService",
                "place",
                "METHOD",
                "OrderService.java",
                42,
                "/work/shop/src/main/java/com/example/OrderService.java");

        assertThat(mapper.writeValueAsString(location))
                .isEqualTo("{\"className\":\"com.example.OrderService\",\"memberName\":\"place\","
                        + "\"kind\":\"METHOD\",\"sourceFile\":\"OrderService.java\",\"line\":42,"
                        + "\"sourcePath\":\"/work/shop/src/main/java/com/example/OrderService.java\","
                        + "\"precision\":\"LINE\"}");
        AdvisorRuleViolationsDto page = new AdvisorRuleViolationsDto(
                "scan",
                "RULE",
                2,
                2,
                false,
                List.of("one", "two"),
                new PageMetadata(2, 2, 0, 100, 2, false),
                Arrays.asList(location, null));
        assertThat(mapper.readTree(mapper.writeValueAsString(page))
                        .path("locations")
                        .get(1)
                        .isNull())
                .isTrue();
        assertThat(mapper.writeValueAsString(page)).contains("\"page\":{").endsWith(",null]}");
    }
}
