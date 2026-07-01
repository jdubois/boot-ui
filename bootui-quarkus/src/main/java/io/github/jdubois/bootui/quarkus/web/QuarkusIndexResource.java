package io.github.jdubois.bootui.quarkus.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the BootUI single-page application shell at {@code /bootui} (no trailing slash).
 *
 * <p>Quarkus' static-resource handler already serves the compiled Vue assets shipped inside
 * {@code bootui-ui} at {@code META-INF/resources/bootui/} — including {@code index.html} at
 * {@code /bootui/} (with the trailing slash) — but it does not redirect or otherwise resolve a request
 * for the bare directory path {@code /bootui}, which previously 404'd. The generated {@code index.html}
 * references its assets relatively (e.g. {@code ./assets/index-*.js}) and the SPA calls its API relatively
 * (e.g. {@code fetch('api/overview')}), so those relative URLs only resolve correctly once the document's
 * base URL ends in {@code /bootui/}.</p>
 *
 * <p>Rather than {@code 302}-redirect {@code /bootui -> /bootui/} (which a proxy that strips trailing
 * slashes could turn into an infinite loop, see the Spring adapter's {@code BootUiIndexController} and
 * #456), this JAX-RS resource answers {@code GET /bootui} directly with the same SPA shell and injects a
 * {@code <base href="{requestPath}/">} tag, mirroring the Spring adapter exactly. The
 * {@code quarkus.http.root-path} prefix (if any) is already part of {@code UriInfo#getRequestUri()}, so the
 * injected base href is correct under a non-default root-path without reading config directly.</p>
 */
@Path("/bootui")
public class QuarkusIndexResource {

    static final String INDEX_LOCATION = "META-INF/resources/bootui/index.html";

    private static final Pattern HEAD_OPEN = Pattern.compile("(?i)<head[^>]*>");

    private static final Pattern EXISTING_BASE = Pattern.compile("(?i)<base\\b");

    private volatile String cachedTemplate;

    @GET
    public Response index(@Context UriInfo uriInfo) {
        String path = uriInfo.getRequestUri().getRawPath();
        String baseHref = path.endsWith("/") ? path : path + "/";
        String html = injectBaseHref(template(), baseHref);
        return Response.ok(html, MediaType.TEXT_HTML_TYPE).build();
    }

    private String template() {
        String html = cachedTemplate;
        if (html == null) {
            html = readTemplate();
            cachedTemplate = html;
        }
        return html;
    }

    private static String readTemplate() {
        try (InputStream in = QuarkusIndexResource.class.getClassLoader().getResourceAsStream(INDEX_LOCATION)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("BootUI index.html not found at " + INDEX_LOCATION));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read BootUI index.html from " + INDEX_LOCATION, ex);
        }
    }

    /**
     * Inserts a {@code <base href>} as the first child of {@code <head>} so it precedes every relative
     * asset/API URL in the document. Returns the markup unchanged when it already declares a
     * {@code <base>} tag or has no {@code <head>}.
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
