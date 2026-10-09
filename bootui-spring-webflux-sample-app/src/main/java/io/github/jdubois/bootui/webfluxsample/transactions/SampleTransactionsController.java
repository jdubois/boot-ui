package io.github.jdubois.bootui.webfluxsample.transactions;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Generates a committed, a slow committed, and a rolled-back transaction in one safe request, for the Transactions
 * panel: the reactive counterpart of the servlet sample's {@code GET /api/sample/transaction-samples}. The blocking
 * transactional work runs on {@code boundedElastic}, never on the Netty event loop.
 */
@RestController
@RequestMapping("/api/sample")
public class SampleTransactionsController {

    private static final Logger logger = LoggerFactory.getLogger(SampleTransactionsController.class);

    private final SampleTransactionScenarios scenarios;

    public SampleTransactionsController(SampleTransactionScenarios scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping("/transaction-samples")
    public Mono<Map<String, Object>> transactionSamples() {
        return Mono.fromCallable(() -> {
                    long committedRows = scenarios.commit();
                    long slowCommittedRows = scenarios.slowCommit();
                    try {
                        scenarios.rollBack();
                        throw new IllegalStateException("The transaction rollback sample unexpectedly committed");
                    } catch (SampleTransactionRollbackException expected) {
                        logger.info("Generated the expected transaction rollback sample");
                    }
                    return Map.<String, Object>of(
                            "scenarios",
                            List.of("committed", "slow committed", "rolled back"),
                            "committedRows",
                            committedRows,
                            "slowCommittedRows",
                            slowCommittedRows,
                            "slowMillis",
                            SampleTransactionScenarios.SLOW_TRANSACTION_MILLIS);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
