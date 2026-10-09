package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSelectionDto;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
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
    void refusesAnIdTheEnabledJournalAndTheBufferBothDoNotRecordAsAnUnknownId() {
        RequestJournalProfileDto missing =
                RequestJournalProfileDto.unavailable("nope", RequestJournalProfiles.notRetainedReason("nope"));
        RequestProfileDto notInBuffer = RequestProfileDto.unavailable("Request nope is no longer in the buffer");

        assertThatThrownBy(() -> RequestProfileSelection.select("nope", id -> missing, id -> notInBuffer))
                .isInstanceOfSatisfying(McpToolClientException.class, refusal -> {
                    assertThat(refusal.status()).isEqualTo(404);
                    assertThat(refusal.getMessage())
                            .isEqualTo(RequestProfileSelection.unknownIdMessage("nope"))
                            .contains("nope", "get_live_activity");
                });
    }

    @Test
    void keepsUnavailableWhenTheJournalIsOffOrTheRequestCannotBeProfiled() {
        RequestJournalProfileDto off = RequestJournalProfileDto.unavailable("r1", RequestJournalProfiles.DISABLED);
        RequestProfileDto notInBuffer = RequestProfileDto.unavailable("Request r1 is no longer in the buffer");
        RequestProfileSelectionDto journalOff = RequestProfileSelection.select("r1", id -> off, id -> notInBuffer);

        assertThat(journalOff.available())
                .as("with the journal off, a missing id says the journal is off rather than that the id is unknown")
                .isFalse();
        assertThat(journalOff.unavailableReason()).contains(RequestJournalProfiles.DISABLED);

        RequestJournalProfileDto missing =
                RequestJournalProfileDto.unavailable("r1", RequestJournalProfiles.notRetainedReason("r1"));
        RequestProfileDto uncorrelated = RequestProfileDto.unavailable("No trace id was captured for r1.");
        assertThat(RequestProfileSelection.select("r1", id -> missing, id -> uncorrelated)
                        .available())
                .as("a buffered request that cannot be profiled is not an unknown id")
                .isFalse();
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
