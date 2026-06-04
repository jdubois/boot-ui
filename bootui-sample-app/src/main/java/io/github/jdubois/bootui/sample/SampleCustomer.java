package io.github.jdubois.bootui.sample;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "sample_advisor_customers")
// Hibernate Advisor demo: the unidirectional invoices collection intentionally triggers HIB-MAP-001.
public class SampleCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private String name;

    @OneToMany
    private List<SampleInvoice> invoices;

    protected SampleCustomer() {}

    public SampleCustomer(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<SampleInvoice> getInvoices() {
        return invoices;
    }
}
