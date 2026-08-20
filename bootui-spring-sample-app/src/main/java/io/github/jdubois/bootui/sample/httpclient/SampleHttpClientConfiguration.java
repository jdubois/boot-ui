package io.github.jdubois.bootui.sample.httpclient;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Registers the sample HTTP Interface clients so the BootUI HTTP Clients panel has real, differentiated rows
 * to describe in the Spring servlet sample application.
 *
 * <p>The two groups deliberately differ so the panel's fixtures cover the states that matter:
 * {@code sample-products} has a client-specific base URL and client-specific timeouts, while
 * {@code sample-inventory} has a base URL built from a property placeholder and no client-specific timeouts,
 * so it inherits the application defaults. Neither client is ever invoked — the panel reports registrations,
 * not traffic.</p>
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "sample-products", types = SampleProductClient.class)
@ImportHttpServices(group = "sample-inventory", types = SampleInventoryClient.class)
class SampleHttpClientConfiguration {}
