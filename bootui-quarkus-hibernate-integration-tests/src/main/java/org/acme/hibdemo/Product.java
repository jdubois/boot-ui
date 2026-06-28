package org.acme.hibdemo;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately <em>imperfect</em> mapped entity that triggers two known engine Hibernate advisories, so the
 * Quarkus advisor integration test can assert real rule output:
 *
 * <ul>
 *   <li>{@code @ManyToOne(fetch = EAGER)} to {@link Category} &rarr; {@code HIB-FETCH-001} (eager association
 *       fetching).</li>
 *   <li>{@code @ManyToMany List<Tag>} &rarr; {@code HIB-MAP-002} (a {@code @ManyToMany} mapped with a
 *       {@code List}).</li>
 * </ul>
 */
@Entity
public class Product {

    @Id
    @GeneratedValue
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    private Category category;

    @ManyToMany
    private List<Tag> tags = new ArrayList<>();

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

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }
}
