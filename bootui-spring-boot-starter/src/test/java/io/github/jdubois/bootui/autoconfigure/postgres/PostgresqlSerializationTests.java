package io.github.jdubois.bootui.autoconfigure.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.PostgresReplicationDto;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class PostgresqlSerializationTests {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replicaAvailabilitySurvivesJackson3Serialization(boolean available) {
        var replication = new PostgresReplicationDto(false, List.of(), null, null, null, null, null, null, available);

        var json = JsonMapper.builder().build().valueToTree(replication);

        assertThat(json.get("replicasAvailable").isBoolean()).isTrue();
        assertThat(json.get("replicasAvailable").asBoolean()).isEqualTo(available);
        assertThat(json.get("replicas").isArray()).isTrue();
        assertThat(json.get("replicas").size()).isZero();
    }
}
