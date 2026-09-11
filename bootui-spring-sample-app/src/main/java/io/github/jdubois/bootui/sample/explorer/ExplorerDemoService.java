package io.github.jdubois.bootui.sample.explorer;

import io.github.jdubois.bootui.sample.catalog.ProductRepository;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExplorerDemoService {
    private final ProductRepository products;
    private final ExplorerDemoRepository repository;

    public ExplorerDemoService(ProductRepository products, ExplorerDemoRepository repository) {
        this.products = products;
        this.repository = repository;
    }

    @Cacheable("explorerProducts")
    @Transactional(readOnly = true)
    public Map<String, Object> product(long id) {
        // A real Spring Data query, including on a cold Hibernate second-level cache.
        long count = products.count();
        String name = repository.name(id);
        return Map.of("id", id, "name", name == null ? "Unknown sample product" : name, "catalogSize", count);
    }

    public void fail() {
        repository.fail();
    }
}
