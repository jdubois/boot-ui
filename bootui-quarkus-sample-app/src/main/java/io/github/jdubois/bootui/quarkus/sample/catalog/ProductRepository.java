package io.github.jdubois.bootui.quarkus.sample.catalog;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Panache repository for {@link Product} (showcases quarkus-hibernate-orm-panache). */
@ApplicationScoped
public class ProductRepository implements PanacheRepository<Product> {

    public List<Product> findActiveOrderByName() {
        return list("active = true order by name");
    }

    public List<Product> searchByName(String term) {
        return list("lower(name) like lower(?1) order by name", "%" + term + "%");
    }

    public long countByCategory(String category) {
        return count("category = ?1", category);
    }
}
