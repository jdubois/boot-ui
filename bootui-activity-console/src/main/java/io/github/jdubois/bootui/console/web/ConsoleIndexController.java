package io.github.jdubois.bootui.console.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Serves the BootUI SPA shell at {@code /bootui} and {@code /bootui/}, injecting a {@code <base href>}
 * so the shell's relative asset/API URLs resolve &mdash; the console's self-contained equivalent of
 * {@code ReactiveBootUiIndexController} (see the package-level Javadoc for why it is not reused
 * directly). Unlike the host-application adapters, the console has no configurable mount path or
 * servlet context path to account for, so the injected {@code <base href>} is always the fixed {@code
 * /bootui/}.
 *
 * <p>Also redirects the bare application root {@code /} straight to the Live Activity panel ({@code
 * /bootui/#/activity}) rather than leaving it unmapped (404) or landing on the shared Vue router's
 * default {@code /overview} route &mdash; which the console always reports unavailable, since Live
 * Activity is the only panel it serves (see {@link ConsolePanelsController}). Redirecting with the
 * target hash already in the {@code Location} header lets the browser navigate straight there in one
 * hop, skipping the router's default-route flash entirely.
 */
@Controller
public class ConsoleIndexController {

    public static final String INDEX_LOCATION = "META-INF/resources/bootui/index.html";

    public static final String ROOT_REDIRECT_LOCATION = "/bootui/#/activity";

    private static final String BASE_HREF = "/bootui/";

    private static final Pattern HEAD_OPEN = Pattern.compile("(?i)<head[^>]*>");

    private static final Pattern EXISTING_BASE = Pattern.compile("(?i)<base\\b");

    private final Resource indexResource;

    private volatile String cachedTemplate;

    public ConsoleIndexController() {
        this(new ClassPathResource(INDEX_LOCATION));
    }

    ConsoleIndexController(Resource indexResource) {
        this.indexResource = indexResource;
    }

    @GetMapping("/")
    public Mono<Void> redirectRootToActivity(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FOUND);
        response.getHeaders().setLocation(URI.create(ROOT_REDIRECT_LOCATION));
        return response.setComplete();
    }

    @GetMapping({"/bootui", "/bootui/"})
    public Mono<Void> spaIndex(ServerWebExchange exchange) {
        String html = injectBaseHref(template(), BASE_HREF);
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
            throw new UncheckedIOException("Unable to read BootUI index.html from " + INDEX_LOCATION, ex);
        }
    }

    /**
     * Inserts a {@code <base href>} as the first child of {@code <head>} so it precedes every relative
     * asset/API URL in the document. Returns the markup unchanged when it already declares a {@code
     * <base>} tag or has no {@code <head>}. Duplicated from {@code BootUiIndexController} (a ~15-line
     * static utility) rather than depending on it &mdash; see the package-level Javadoc.
     */
    static String injectBaseHref(String html, String baseHref) {
        if (EXISTING_BASE.matcher(html).find()) {
            return html;
        }
        Matcher matcher = HEAD_OPEN.matcher(html);
        if (!matcher.find()) {
            return html;
        }
        int insertAt = matcher.end();
        String baseTag = "\n    <base href=\"" + escapeAttribute(baseHref) + "\" />";
        return html.substring(0, insertAt) + baseTag + html.substring(insertAt);
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
