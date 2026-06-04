package io.github.jdubois.bootui.sample;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;

@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
// Hibernate Advisor demo: TABLE_PER_CLASS inheritance intentionally triggers HIB-MAP-007.
public abstract class SampleBillingDocument {

    @Id
    private Long id;

    private String reference;

    protected SampleBillingDocument() {}

    protected SampleBillingDocument(Long id, String reference) {
        this.id = id;
        this.reference = reference;
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }
}
