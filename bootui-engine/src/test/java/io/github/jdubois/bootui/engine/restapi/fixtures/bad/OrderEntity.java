package io.github.jdubois.bootui.engine.restapi.fixtures.bad;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** A JPA entity that should never be exposed in the web layer. */
@Entity
public class OrderEntity {

    @Id
    private Long id;

    private String customer;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCustomer() {
        return customer;
    }

    public void setCustomer(String customer) {
        this.customer = customer;
    }
}
