package io.github.jdubois.bootui.sample.httpclient;

import java.util.List;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * A second Spring HTTP Interface, registered under a different group than {@link SampleProductClient}, so the
 * HTTP Clients panel demonstrates multiple named clients with distinct identities, distinct base URLs and
 * distinct effective settings.
 *
 * <p>Its group intentionally has <em>no</em> client-specific timeout configuration, so the panel shows an
 * inherited application default next to {@code SampleProductClient}'s client-specific override.</p>
 *
 * @see SampleHttpClientConfiguration
 */
@HttpExchange("/api/inventory")
public interface SampleInventoryClient {

    @GetExchange
    List<String> inventory();
}
