package io.github.jdubois.bootui.sample.web;

import io.github.jdubois.bootui.sample.catalog.ProductSummary;
import io.github.jdubois.bootui.sample.catalog.SampleCatalog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {

    private final SampleCatalog catalog;

    public HelloController(SampleCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/hello")
    public String hello() {
        return "Hello, world";
    }

    @GetMapping("/secure")
    public String secure() {
        return "Secure Hello, world";
    }

    @GetMapping("/secure/products")
    public List<ProductSummary> secureProducts() {
        // Authenticated (ROLE_ADMIN) AND backed by a live SQL query, so a single request links a
        // Spring Security event to SQL statements in the BootUI Live Activity profiler.
        return catalog.securedCatalog();
    }
}
