package io.github.jdubois.bootui.sample;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "sample_advisor_orders")
// Hibernate Advisor demo: this class intentionally triggers HIB-FETCH-001, HIB-MAP-002, and HIB-MAP-003.
public class SampleOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    private SampleCustomer customer;

    @ManyToMany
    private List<SampleTag> tags;

    private SampleOrderStatus status;

    protected SampleOrder() {}

    public SampleOrder(SampleCustomer customer, SampleOrderStatus status) {
        this.customer = customer;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public SampleCustomer getCustomer() {
        return customer;
    }

    public List<SampleTag> getTags() {
        return tags;
    }

    public SampleOrderStatus getStatus() {
        return status;
    }
}
