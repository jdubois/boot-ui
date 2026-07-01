package org.acme.hibdemo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/**
 * A trivial mapped entity used only to give the Quarkus Hibernate advisor integration test a real JPA metamodel
 * to analyse. Intentionally clean (a generated {@code Long} identifier) so it contributes to
 * {@code entitiesAnalyzed} without itself producing a known advisory.
 */
@Entity
public class Category {

    @Id
    @GeneratedValue
    private Long id;

    private String name;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
