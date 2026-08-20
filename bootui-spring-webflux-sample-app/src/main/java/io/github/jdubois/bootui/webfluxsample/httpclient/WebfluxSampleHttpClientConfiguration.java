package io.github.jdubois.bootui.webfluxsample.httpclient;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Registers the WebFlux sample's HTTP Interface client so the BootUI HTTP Clients panel is exercised on the
 * reactive stack too, proving that the shared engine, DTO contract and UI behave identically there.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "sample-notes", types = WebfluxSampleNoteClient.class)
class WebfluxSampleHttpClientConfiguration {}
