package io.github.jdubois.bootui.sample.insights;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "insight_eager_orders")
public class EagerDemoOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String description;

    // Deliberate demo finding: loading orders without a fetch plan can issue a SELECT per customer.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private EagerDemoCustomer customer;

    protected EagerDemoOrder() {}

    EagerDemoOrder(String description, EagerDemoCustomer customer) {
        this.description = description;
        this.customer = customer;
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public EagerDemoCustomer getCustomer() {
        return customer;
    }
}
