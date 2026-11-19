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
 *     any scheme/port/brackets; may be {@code null} or blank (only a genuinely missing or blank Host
 *     is allowed — a present but malformed value is rejected)
 * @param origin the raw {@code Origin} header value; may be {@code null}
 * @param secFetchSite the raw {@code Sec-Fetch-Site} header value; may be {@code null}
 * @param scheme the effective HTTP scheme used for Origin comparison
 * @param rawHostAvailable whether the adapter can distinguish the original authority from a rewritten one
 */
public record LocalhostGuardRequest(
        String method,
        String remoteAddr,
        String hostAuthority,
        String origin,
        String secFetchSite,
        String scheme,
        boolean rawHostAvailable) {

    public LocalhostGuardRequest(
            String method, String remoteAddr, String hostAuthority, String origin, String secFetchSite, String scheme) {
        this(method, remoteAddr, hostAuthority, origin, secFetchSite, scheme, true);
    }

    public LocalhostGuardRequest(
            String method, String remoteAddr, String hostAuthority, String origin, String secFetchSite) {
        this(method, remoteAddr, hostAuthority, origin, secFetchSite, "http");
    }
}
