package io.github.jdubois.bootui.sample;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sample_advisor_invoices")
public class SampleInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private String reference;

    protected SampleInvoice() {}

    public SampleInvoice(String reference) {
        this.reference = reference;
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }
}
