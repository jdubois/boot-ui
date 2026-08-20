package io.github.jdubois.bootui.webfluxsample.httpclient;

import java.util.List;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * A Spring HTTP Interface registered in the WebFlux sample application so the BootUI HTTP Clients panel has a
 * real declarative client to describe on the reactive stack. It is never invoked: the panel reports
 * registrations and effective configuration, not traffic.
 *
 * @see WebfluxSampleHttpClientConfiguration
 */
@HttpExchange("/api/notes")
public interface WebfluxSampleNoteClient {

    @GetExchange
    List<String> notes();
}
