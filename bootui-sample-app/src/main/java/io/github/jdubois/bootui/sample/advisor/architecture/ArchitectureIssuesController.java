package io.github.jdubois.bootui.sample.advisor.architecture;

import io.github.jdubois.bootui.sample.catalog.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/architecture")
public class ArchitectureIssuesController {

    // This should trigger ARCH-SPRING-001
    @Autowired
    // This should trigger ARCH-SPRING-002
    private ProductRepository repository;

    @RequestMapping("/some-errors")
    public String errors() {
        // This should trigger ARCH-CODE-001
        System.out.println("This should trigger ARCH-CODE-001");
        return "";
    }
}
