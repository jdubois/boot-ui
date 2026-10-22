package io.github.jdubois.bootui.sample.advisor.hibernate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sample_advisor_tags")
public class SampleTag {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private String label;

    protected SampleTag() {}

    public SampleTag(String label) {
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    /** Changes the label of a managed tag, so Hibernate finds it dirty at the next flush. */
    public void relabel(String label) {
        this.label = label;
    }
}
