package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.BootUiIndexController;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@link BootUiIndexController}: serves the same compiled SPA shell at
 * {@code /bootui} and {@code /bootui/}, writing the response reactively instead of through
 * {@code HttpServletResponse}. Reuses {@link BootUiIndexController}'s well-tested
 * {@code injectBaseHref} rewriting so the two adapters cannot diverge on the shared shell markup; see
 * that class's Javadoc for why the shell is served directly rather than redirected.
 *
 * <p>WebFlux has no {@code server.servlet.context-path} analog, so unlike the servlet controller this
 * never prepends a context path to the injected {@code <base href>} &mdash; {@code properties.getPath()}
 * is always application-relative here.</p>
 */
@Controller
public class ReactiveBootUiIndexController {

    private final BootUiProperties properties;

    private final Resource indexResource;

    private volatile String cachedTemplate;

    @Autowired
    public ReactiveBootUiIndexController(BootUiProperties properties) {
        this(properties, new ClassPathResource(BootUiIndexController.INDEX_LOCATION));
    }

    ReactiveBootUiIndexController(BootUiProperties properties, Resource indexResource) {
        this.properties = properties;
        this.indexResource = indexResource;
    }

    @GetMapping({"/bootui", "/bootui/"})
    public Mono<Void> spaIndex(ServerWebExchange exchange) {
        String baseHref = properties.getPath() + "/";
        String html = BootUiIndexController.injectBaseHref(template(), baseHref);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().setContentType(MediaType.TEXT_HTML);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private String template() {
        String html = cachedTemplate;
        if (html == null) {
            html = readTemplate();
            cachedTemplate = html;
        }
        return html;
    }

    private String readTemplate() {
        try (var in = indexResource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Unable to read BootUI index.html from " + BootUiIndexController.INDEX_LOCATION, ex);
        }
    }
}
