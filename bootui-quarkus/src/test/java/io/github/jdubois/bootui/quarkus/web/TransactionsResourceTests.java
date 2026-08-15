package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.TransactionReport;
import org.junit.jupiter.api.Test;

class TransactionsResourceTests {

    @Test
    void alwaysReportsUnavailableWithAClearReason() {
        TransactionsResource resource = new TransactionsResource();

        TransactionReport report = resource.transactions();

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo(TransactionsResource.UNAVAILABLE_REASON);
        assertThat(report.entries()).isEmpty();
    }
}
