package io.github.jdubois.bootui.sample.advisor.hibernate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sample_advisor_order_details")
public class SampleOrderDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private String instructions;

    protected SampleOrderDetails() {}

    public SampleOrderDetails(String instructions) {
        this.instructions = instructions;
    }

    public Long getId() {
        return id;
    }

    public String getInstructions() {
        return instructions;
    }
}
