package io.github.jdubois.bootui.sample.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sample_products")
// Hibernate Advisor demo: the IDENTITY id below intentionally triggers HIB-ID-001.
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;

    public String category;

    public boolean active;

    public Product() {}

    public Product(String name, String category, boolean active) {
        this.name = name;
        this.category = category;
        this.active = active;
    }
}
