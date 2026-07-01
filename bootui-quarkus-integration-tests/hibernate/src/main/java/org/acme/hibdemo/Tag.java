package org.acme.hibdemo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/**
 * A trivial mapped entity used only to give the Quarkus Hibernate advisor integration test a real JPA metamodel
 * to analyse, and to be the {@code @ManyToMany} target of {@link Product}.
 */
@Entity
public class Tag {

    @Id
    @GeneratedValue
    private Long id;

    private String label;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
