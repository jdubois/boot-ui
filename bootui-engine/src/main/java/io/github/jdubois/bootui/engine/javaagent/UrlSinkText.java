package io.github.jdubois.bootui.engine.javaagent;

/**
 * An outbound URL's target for a {@code security-sinks} row ({@code docs/PLAN-v2.md} §5.16, M5-6b), from the URL already
 * redacted: its scheme, host, port, and path, and its query's keys only, as the REST client panel groups calls. User
 * information, query values, matrix parameter values, and the fragment are dropped, so no value the request did not
 * send reaches the row either.
 */
final class UrlSinkText {

    private UrlSinkText() {}

    static String normalize(String redacted) {
        if (redacted == null) {
            return null;
        }
        String url = redacted;
        int hash = url.indexOf('#');
        if (hash >= 0) {
            url = url.substring(0, hash);
        }
        String query = null;
        int question = url.indexOf('?');
        if (question >= 0) {
            query = url.substring(question + 1);
            url = url.substring(0, question);
        }
        int scheme = url.indexOf("://");
        String prefix = "";
        String rest = url;
        if (scheme > 0) {
            prefix = url.substring(0, scheme + 3);
            rest = url.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        // Matrix parameters keep their names only, as the query does.
        path = path.replaceAll(";([^;/=]*)=[^;/]*", ";$1");
        StringBuilder out = new StringBuilder(prefix).append(authority).append(path);
        if (query != null && !query.isEmpty()) {
            out.append('?');
            boolean first = true;
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                if (!first) {
                    out.append('&');
                }
                out.append(equals < 0 ? pair : pair.substring(0, equals));
                first = false;
            }
        }
        return out.toString();
    }
}
