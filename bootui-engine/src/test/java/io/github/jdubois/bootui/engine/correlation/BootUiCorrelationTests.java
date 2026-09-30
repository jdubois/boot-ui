package io.github.jdubois.bootui.engine.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation.Scope;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BootUiCorrelationTests {

    @AfterEach
    void clearThread() {
        BootUiCorrelation.replace(CorrelationContext.NONE);
    }

    @Test
    void currentIsNoneWhenNoScopeIsOpen() {
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void scopeMakesItsContextCurrentAndRestoresNoneOnClose() {
        CorrelationContext request = CorrelationContext.forRequest("r1");

        try (Scope ignored = BootUiCorrelation.open(request)) {
            assertThat(BootUiCorrelation.current()).isEqualTo(request);
        }

        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void nestedScopesRestoreTheEnclosingContext() {
        CorrelationContext request = CorrelationContext.forRequest("r1");
        CorrelationContext transaction = request.withTransactionId("tx1");

        try (Scope outer = BootUiCorrelation.open(request)) {
            try (Scope inner = BootUiCorrelation.open(transaction)) {
                assertThat(BootUiCorrelation.current().transactionId()).isEqualTo("tx1");
            }
            assertThat(BootUiCorrelation.current()).isEqualTo(request);
        }

        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void scopeRestoresThePreviousContextWhenTheWorkThrows() {
        CorrelationContext request = CorrelationContext.forRequest("r1");

        try (Scope ignored = BootUiCorrelation.open(request)) {
            throw new IllegalStateException("boom");
        } catch (IllegalStateException expected) {
            assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
        }
    }

    @Test
    void closingAScopeTwiceDoesNotRestoreTwice() {
        CorrelationContext request = CorrelationContext.forRequest("r1");
        CorrelationContext nested = request.withDataSource("primary");

        try (Scope outer = BootUiCorrelation.open(request)) {
            Scope inner = BootUiCorrelation.open(nested);
            inner.close();
            BootUiCorrelation.open(nested);
            inner.close();

            assertThat(BootUiCorrelation.current()).isEqualTo(nested);
        }
    }

    @Test
    void nullAndEmptyContextsClearTheThread() {
        try (Scope ignored = BootUiCorrelation.open(null)) {
            assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
        }

        CorrelationContext previous = BootUiCorrelation.replace(CorrelationContext.forRequest("r1"));
        assertThat(previous).isSameAs(CorrelationContext.NONE);
        assertThat(BootUiCorrelation.replace(new CorrelationContext(null, null, null, null, null, null, null, null)))
                .isEqualTo(CorrelationContext.forRequest("r1"));
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void contextsAreIsolatedBetweenThreads() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch bothOpen = new CountDownLatch(2);
            Future<String> first = executor.submit(() -> readInsideScope("r1", bothOpen));
            Future<String> second = executor.submit(() -> readInsideScope("r2", bothOpen));

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("r1");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("r2");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aPooledThreadCarriesNoContextIntoItsNextTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                        try (Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
                            return BootUiCorrelation.current().requestId();
                        }
                    })
                    .get(5, TimeUnit.SECONDS);

            CorrelationContext next =
                    executor.submit(BootUiCorrelation::current).get(5, TimeUnit.SECONDS);

            assertThat(next).isSameAs(CorrelationContext.NONE);
        } finally {
            executor.shutdownNow();
        }
    }

    private static String readInsideScope(String requestId, CountDownLatch bothOpen) throws InterruptedException {
        try (Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(requestId))) {
            bothOpen.countDown();
            bothOpen.await(5, TimeUnit.SECONDS);
            return BootUiCorrelation.current().requestId();
        }
    }
}
