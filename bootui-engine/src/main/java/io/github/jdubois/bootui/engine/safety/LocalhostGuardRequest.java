package io.github.jdubois.bootui.engine.safety;

/**
 * The raw, framework-neutral request facts the {@link LocalhostGuard} needs to make an allow/reject
 * decision. Adapters populate this from their native request type, passing <em>raw</em> header
 * strings: the guard owns <em>all</em> parsing (host extraction, method casing, cross-site
 * evaluation) so both adapters share one parser and cannot diverge.
 *
 * @param method the HTTP method (for example {@code GET}, {@code POST}); may be {@code null}
 * @param remoteAddr the raw TCP peer address of the socket (never a forwarded header); may be
 *     {@code null} or blank, both of which the guard treats as an untrusted source
 * @param hostAuthority the raw {@code Host} (or HTTP/2 {@code :authority}) header value, including
 *     any scheme/port/brackets; may be {@code null} (a missing Host is allowed)
 * @param origin the raw {@code Origin} header value; may be {@code null}
 * @param secFetchSite the raw {@code Sec-Fetch-Site} header value; may be {@code null}
 */
public record LocalhostGuardRequest(
        String method, String remoteAddr, String hostAuthority, String origin, String secFetchSite) {}
