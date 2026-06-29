package io.github.jdubois.bootui.quarkus.mappings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MappingDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure row-to-DTO mapping in {@link QuarkusMappingProvider}. The provider's CDI plumbing (an
 * unsatisfied {@code Instance} yielding {@code available()==false} / an empty report) is exercised
 * end-to-end by the {@code @QuarkusTest} {@code BootUiQuarkusMappingsResourceTest}; here we verify only
 * that each build-time-captured {@link RawMapping} maps one-to-one onto the neutral {@link MappingDto}
 * contract, preserving every field (including nullable produces/consumes).
 */
class QuarkusMappingProviderTest {

    @Test
    void mapsEveryFieldOneToOne() {
        RawMapping row = new RawMapping(
                "GET", "/widgets", "org.acme.restdemo.WidgetResource#list", "application/json", "text/plain");

        List<MappingDto> dtos = QuarkusMappingProvider.toDtos(List.of(row));

        assertThat(dtos).singleElement().satisfies(dto -> {
            assertThat(dto.method()).isEqualTo("GET");
            assertThat(dto.pattern()).isEqualTo("/widgets");
            assertThat(dto.handler()).isEqualTo("org.acme.restdemo.WidgetResource#list");
            assertThat(dto.produces()).isEqualTo("application/json");
            assertThat(dto.consumes()).isEqualTo("text/plain");
        });
    }

    @Test
    void preservesNullMediaTypesAndAnyMethodAndEncounterOrder() {
        List<RawMapping> rows = List.of(
                new RawMapping("ANY", "/sub", "org.acme.restdemo.SubResource#locator", null, null),
                new RawMapping(
                        "POST", "/widgets", "org.acme.restdemo.WidgetResource#create", "application/json", null));

        List<MappingDto> dtos = QuarkusMappingProvider.toDtos(rows);

        assertThat(dtos).extracting(MappingDto::pattern).containsExactly("/sub", "/widgets");
        assertThat(dtos.get(0).method()).isEqualTo("ANY");
        assertThat(dtos.get(0).produces()).isNull();
        assertThat(dtos.get(0).consumes()).isNull();
        assertThat(dtos.get(1).consumes()).isNull();
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertThat(QuarkusMappingProvider.toDtos(List.of())).isEmpty();
    }
}
