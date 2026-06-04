package io.github.jdubois.bootui.sample;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "sample_advisor_receipts")
public class SampleReceipt extends SampleBillingDocument {

    private String paymentReference;

    protected SampleReceipt() {}

    public SampleReceipt(Long id, String reference, String paymentReference) {
        super(id, reference);
        this.paymentReference = paymentReference;
    }

    public String getPaymentReference() {
        return paymentReference;
    }
}
