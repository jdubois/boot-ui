package io.github.jdubois.bootui.engine.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.CorrelationIdDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Propagation of a request's opaque correlation identities onto the entries already correlated with it. */
class ActivityCorrelationPropagationTests {

    private static final String LOOKUP = CorrelationIdPolicy.lookupId("corr-1");

    private ActivityEntryDto request(String id, List<String> lookupIds) {
        return new ActivityEntryDto(
                id,
                "REQUEST",
                1_000L,
                "OK",
                "GET /orders",
                null,
                5L,
                "trace-1",
                "GET",
                "/orders",
                200,
                null,
                true,
                null,
                null,
                false,
                lookupIds.isEmpty()
                        ? List.of()
                        : List.of(new CorrelationIdDto("x-correlation-id", "******", true, false, LOOKUP)),
                lookupIds);
    }

    private ActivityEntryDto child(String id, String parentId) {
        return new ActivityEntryDto(
                id,
                "SQL",
                1_001L,
                "OK",
                "select 1",
                null,
                1L,
                "trace-1",
                null,
                null,
                null,
                null,
                false,
                parentId,
                null,
                false);
    }

    @Test
    void stampsCorrelatedChildrenWithTheOwningRequestIdentities() {
        List<ActivityEntryDto> result =
                ActivityCorrelationPropagation.propagate(List.of(request("r1", List.of(LOOKUP)), child("s1", "r1")));

        assertThat(result.get(0).correlationLookupIds()).containsExactly(LOOKUP);
        assertThat(result.get(1).correlationLookupIds()).containsExactly(LOOKUP);
        assertThat(result.get(1).correlationIds()).isEmpty();
    }

    @Test
    void leavesUncorrelatedEntriesAlone() {
        List<ActivityEntryDto> result = ActivityCorrelationPropagation.propagate(
                List.of(request("r1", List.of(LOOKUP)), child("s1", null), child("s2", "missing-parent")));

        assertThat(result.get(1).correlationLookupIds()).isEmpty();
        assertThat(result.get(2).correlationLookupIds()).isEmpty();
    }

    @Test
    void leavesChildrenOfIdentifierFreeRequestsAlone() {
        List<ActivityEntryDto> result = ActivityCorrelationPropagation.propagate(
                List.of(request("r1", List.of()), request("r2", List.of(LOOKUP)), child("s1", "r1")));

        assertThat(result.get(2).correlationLookupIds()).isEmpty();
    }

    @Test
    void followsAGrandparentChain() {
        ActivityEntryDto grandChild = child("e1", "s1");

        List<ActivityEntryDto> result = ActivityCorrelationPropagation.propagate(
                List.of(request("r1", List.of(LOOKUP)), child("s1", "r1"), grandChild));

        assertThat(result.get(2).correlationLookupIds()).containsExactly(LOOKUP);
    }

    @Test
    void survivesACyclicParentChain() {
        ActivityEntryDto a = child("a", "b");
        ActivityEntryDto b = child("b", "a");

        List<ActivityEntryDto> result =
                ActivityCorrelationPropagation.propagate(List.of(request("r1", List.of(LOOKUP)), a, b));

        assertThat(result.get(1).correlationLookupIds()).isEmpty();
        assertThat(result.get(2).correlationLookupIds()).isEmpty();
    }

    @Test
    void isAnIdentityPassThroughWhenNothingCarriesAnIdentifier() {
        List<ActivityEntryDto> entries = List.of(request("r1", List.of()), child("s1", "r1"));

        assertThat(ActivityCorrelationPropagation.propagate(entries)).isSameAs(entries);
        assertThat(ActivityCorrelationPropagation.propagate(null)).isEmpty();
        assertThat(ActivityCorrelationPropagation.propagate(List.of())).isEmpty();
    }

    @Test
    void stopsStampingChildrenOnceTheOwningRequestHasBeenEvicted() {
        // The exchange buffer is bounded, so a long-lived child can outlive the request that carried the
        // identifier. Propagation is recomputed per response from the entries actually present, so the
        // child simply stops matching the filter rather than keeping a stale identity around.
        List<ActivityEntryDto> beforeEviction =
                ActivityCorrelationPropagation.propagate(List.of(request("r1", List.of(LOOKUP)), child("s1", "r1")));
        assertThat(beforeEviction.get(1).correlationLookupIds()).containsExactly(LOOKUP);

        List<ActivityEntryDto> afterEviction = ActivityCorrelationPropagation.propagate(List.of(child("s1", "r1")));

        assertThat(afterEviction.get(0).correlationLookupIds()).isEmpty();
    }
}
