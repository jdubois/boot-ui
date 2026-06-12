package io.github.jdubois.bootui.sample.catalog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sample")
public class SampleController {

    private final SampleSettings settings;
    private final SampleCatalog catalog;

    public SampleController(SampleSettings settings, SampleCatalog catalog) {
        this.settings = settings;
        this.catalog = catalog;
    }

    @GetMapping("/hello")
    public String hello() {
        return catalog.greeting(settings.getGreeting(), settings.getRetries());
    }

    @GetMapping("/products")
    public List<ProductSummary> products() {
        return catalog.activeProducts();
    }

    @GetMapping("/product-search")
    public List<ProductSummary> productSearch(@RequestParam(name = "term", defaultValue = "console") String term) {
        return catalog.searchProducts(term);
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object previousClickCount = session.getAttribute("sampleClickCount");
        int sampleClickCount = previousClickCount instanceof Number number ? number.intValue() + 1 : 1;
        session.setAttribute("sampleMessage", "Hello from the sample session");
        session.setAttribute("sampleCount", 42);
        session.setAttribute("sampleClickCount", sampleClickCount);
        session.setAttribute("sampleGeneratedAt", Instant.now().toString());
        session.setAttribute("apiToken", "sample-secret-token");
        return Map.of(
                "sessionId",
                session.getId(),
                "attributeCount",
                5,
                "sampleClickCount",
                sampleClickCount,
                "attributes",
                List.of("sampleMessage", "sampleCount", "sampleClickCount", "sampleGeneratedAt", "apiToken"));
    }

    @GetMapping("/boom")
    public String boom() {
        try {
            Integer.parseInt("not-a-number");
            return "unreachable";
        } catch (NumberFormatException cause) {
            throw new IllegalStateException(
                    "Sample failure for the BootUI Exceptions panel demo (apiToken=sample-secret-token)", cause);
        }
    }
}
