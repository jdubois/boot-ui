package io.github.jdubois.bootui.quarkus.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import org.junit.jupiter.api.Test;

class SqlTraceSerializationTests {

    @Test
    void internalPreparationProvenanceDoesNotChangeTheJackson2PanelContract() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 10, 100, 2_000, 200, 5);
        recorder.recordPreparation("select * from orders", "db");
        var json = new ObjectMapper().valueToTree(recorder.report(false));
        var entry = json.get("entries").get(0);

        assertThat(entry.size()).isEqualTo(SqlTraceEntryDto.class.getRecordComponents().length);
        for (var field : SqlTraceEntryDto.class.getRecordComponents()) {
            assertThat(entry.get(field.getName())).as(field.getName()).isNotNull();
        }
        assertThat(entry.get("provenance")).isNull();
        assertThat(json.get("totalCaptured").asLong()).isEqualTo(1);
        assertThat(json.get("stats").get("totalQueries").asLong()).isZero();
        assertThat(json.get("warnings").get(0).asText()).contains("preparation only");
    }
}
