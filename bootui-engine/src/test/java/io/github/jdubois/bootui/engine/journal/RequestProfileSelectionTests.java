package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSelectionDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestProfileSelectionTests {

    @Test
    void journalExemplarSurvivesWhenTheExchangeBufferDoesNot() {
        RequestJournalProfileDto retained = new RequestJournalProfileDto(
                true,
                null,
                "job-1",
                "Scheduled: work",
                1L,
                100L,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                null);
        RequestProfileSelectionDto selected = RequestProfileSelection.select("job-1", id -> retained, id -> {
            throw new AssertionError("journal hit must not read the HTTP-exchange buffer");
        });

        assertThat(selected.source()).isEqualTo("journal");
        assertThat(selected.journal()).isSameAs(retained);
        assertThat(selected.buffers()).isNull();
    }

    @Test
    void fallsBackToTheBufferAndExplainsWhenNeitherSourceRetainsTheId() {
        RequestJournalProfileDto missing = RequestJournalProfileDto.unavailable("r1", "Journal evicted r1.");
        RequestProfileDto unavailable = RequestProfileDto.unavailable("Request r1 is no longer in the buffer.");
        RequestProfileSelectionDto absent = RequestProfileSelection.select("r1", id -> missing, id -> unavailable);

        assertThat(absent.available()).isFalse();
        assertThat(absent.source()).isEqualTo("none");
        assertThat(absent.unavailableReason()).contains("r1", "Journal evicted", "buffer");
        assertThat(absent.journal()).isNull();
        assertThat(absent.buffers()).isNull();
    }

    @Test
    void keepsTheHttpEvidenceWhenBothProfilesExistAndResolvesAnExchangeAlias() {
        RequestJournalProfileDto retained = new RequestJournalProfileDto(
                true,
                null,
                "request-1",
                "GET /orders",
                1L,
                100L,
                200,
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                null);
        RequestProfileDto detail = new RequestProfileDto(
                true,
                null,
                new HttpExchangeDto(
                        "exchange-1",
                        Instant.EPOCH,
                        "GET",
                        "/orders",
                        null,
                        null,
                        200,
                        null,
                        10L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        "request-1"),
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of(),
                null,
                null,
                List.of());

        RequestProfileSelectionDto direct = RequestProfileSelection.select("request-1", id -> retained, id -> detail);
        assertThat(direct.source()).isEqualTo("journal");
        assertThat(direct.buffers()).isSameAs(detail);
        RequestProfileSelectionDto byExchange = RequestProfileSelection.select(
                "exchange-1",
                id -> id.equals("request-1") ? retained : RequestJournalProfileDto.unavailable(id, "not found"),
                id -> detail);
        assertThat(byExchange.source()).isEqualTo("journal");
        assertThat(byExchange.journal()).isSameAs(retained);
        assertThat(byExchange.buffers()).isSameAs(detail);

        RequestProfileSelectionDto fallback = RequestProfileSelection.select(
                "exchange-1", id -> RequestJournalProfileDto.unavailable(id, "not found"), id -> detail);
        assertThat(fallback.source()).isEqualTo("buffers");
        assertThat(fallback.buffers()).isSameAs(detail);
    }
}
