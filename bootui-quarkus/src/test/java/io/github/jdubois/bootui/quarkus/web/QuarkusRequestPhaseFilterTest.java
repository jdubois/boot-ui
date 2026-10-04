package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/**
 * Which Quarkus resource methods only assemble their result ({@code docs/PLAN-v2.md} §5.14, M5-4b): their Code Paths
 * trees are assembly only, while a method returning a plain value on a worker is timed as executed.
 */
class QuarkusRequestPhaseFilterTest {

    @Test
    void mutinyCompletionStagesAndPublishersAreAsynchronousResults() {
        assertThat(QuarkusRequestPhaseFilter.asynchronous(Uni.class)).isTrue();
        assertThat(QuarkusRequestPhaseFilter.asynchronous(Multi.class)).isTrue();
        assertThat(QuarkusRequestPhaseFilter.asynchronous(CompletionStage.class))
                .isTrue();
        assertThat(QuarkusRequestPhaseFilter.asynchronous(CompletableFuture.class))
                .as("through its interface")
                .isTrue();
        assertThat(QuarkusRequestPhaseFilter.asynchronous(Flow.Publisher.class)).isTrue();
        assertThat(QuarkusRequestPhaseFilter.asynchronous(
                        Uni.createFrom().item(1).getClass()))
                .as("an implementation, through its superclass")
                .isTrue();
    }

    @Test
    void plainValuesAreNot() {
        assertThat(QuarkusRequestPhaseFilter.asynchronous(String.class)).isFalse();
        assertThat(QuarkusRequestPhaseFilter.asynchronous(List.class)).isFalse();
        assertThat(QuarkusRequestPhaseFilter.asynchronous(int.class)).isFalse();
        assertThat(QuarkusRequestPhaseFilter.asynchronous(void.class)).isFalse();
        assertThat(QuarkusRequestPhaseFilter.asynchronous(null)).isFalse();
    }

    /** A resource method's return type is walked once, then answered from the cache on every later request. */
    @Test
    void eachReturnTypeIsWalkedOnce() {
        class Quote {}
        long before = QuarkusRequestPhaseFilter.WALKS.get();
        assertThat(QuarkusRequestPhaseFilter.asynchronous(Quote.class)).isFalse();
        long walked = QuarkusRequestPhaseFilter.WALKS.get();
        assertThat(walked - before).as("the first lookup walks it").isEqualTo(1);
        for (int i = 0; i < 100; i++) {
            assertThat(QuarkusRequestPhaseFilter.asynchronous(Quote.class)).isFalse();
        }
        assertThat(QuarkusRequestPhaseFilter.WALKS.get())
                .as("later lookups never walk it")
                .isEqualTo(walked);
    }
}
