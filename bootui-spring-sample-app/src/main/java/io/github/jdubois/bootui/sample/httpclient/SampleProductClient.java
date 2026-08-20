package io.github.jdubois.bootui.sample.httpclient;

import java.util.List;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * A Spring HTTP Interface aimed at the companion BootUI Quarkus sample app, so the HTTP Clients panel has a
 * real declarative client to describe. It is never called by the sample application: the panel reads
 * registrations, not traffic, and this interface exists precisely to prove that opening the panel does not
 * instantiate or invoke a client.
 *
 * @see SampleHttpClientConfiguration
 */
@HttpExchange("/api/products")
public interface SampleProductClient {

    @GetExchange
    List<String> products();
}
