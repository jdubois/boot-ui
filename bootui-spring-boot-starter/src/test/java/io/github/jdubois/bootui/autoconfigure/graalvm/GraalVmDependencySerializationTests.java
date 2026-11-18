package io.github.jdubois.bootui.autoconfigure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.GraalVmDependencyDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GraalVmDependencySerializationTests {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void springAotSourceRoundTripsIndependentlyOfBundledMetadataWithJackson3() {
        for (boolean shipsMetadata : List.of(false, true)) {
            GraalVmDependencyDto dependency = new GraalVmDependencyDto(
                    "library.jar",
                    shipsMetadata,
                    "Source inventory only.",
                    "com.example:library:1.0",
                    false,
                    null,
                    null,
                    null,
                    null,
                    true);

            String json = mapper.writeValueAsString(dependency);

            assertThat(json)
                    .isEqualTo("{\"name\":\"library.jar\",\"shipsMetadata\":" + shipsMetadata
                            + ",\"note\":\"Source inventory only.\",\"coordinates\":\"com.example:library:1.0\","
                            + "\"repositoryMetadata\":false,\"repositoryMetadataVersion\":null,"
                            + "\"repositoryTestedVersions\":null,\"repositoryUrl\":null,\"repositoryMetadataUrl\":null,"
                            + "\"shipsSpringAotFactories\":true}");
            assertThat(mapper.readValue(json, GraalVmDependencyDto.class)).isEqualTo(dependency);
        }
    }

    @Test
    void legacyConstructorsAndResponsesRoundTripWithNoSpringAotSourceWithJackson3() {
        List<GraalVmDependencyDto> dependencies = List.of(
                new GraalVmDependencyDto("short.jar", true, "Bundled metadata."),
                new GraalVmDependencyDto(
                        "medium.jar", false, "No bundled metadata.", "com.example:library:1.0", false, null, null),
                new GraalVmDependencyDto(
                        "full.jar",
                        false,
                        "Repository match.",
                        "com.example:library:1.0",
                        true,
                        "1.0",
                        "1.0",
                        "https://example.test/entry",
                        "https://example.test/metadata"));
        for (GraalVmDependencyDto dependency : dependencies) {
            String json = mapper.writeValueAsString(dependency);

            assertThat(mapper.readTree(json).path("shipsSpringAotFactories").asBoolean())
                    .isFalse();
            assertThat(mapper.readValue(json, GraalVmDependencyDto.class)).isEqualTo(dependency);
            String legacyJson = json.replace(",\"shipsSpringAotFactories\":false", "");
            assertThat(legacyJson).doesNotContain("shipsSpringAotFactories");
            assertThat(mapper.readValue(legacyJson, GraalVmDependencyDto.class)).isEqualTo(dependency);
            String unknownSourceJson =
                    json.replace("\"shipsSpringAotFactories\":false", "\"shipsSpringAotFactories\":null");
            assertThat(mapper.readValue(unknownSourceJson, GraalVmDependencyDto.class))
                    .isEqualTo(dependency);
        }
    }
}
