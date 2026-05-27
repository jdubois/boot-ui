package io.github.jdubois.bootui.sample;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByActiveTrueOrderByNameAsc();

    List<Product> findByCategoryIgnoreCase(String category);

    long countByCategory(String category);

    @Query("select p from Product p where lower(p.name) like lower(concat('%', :term, '%')) order by p.name")
    List<Product> searchByName(@Param("term") String term);
}
