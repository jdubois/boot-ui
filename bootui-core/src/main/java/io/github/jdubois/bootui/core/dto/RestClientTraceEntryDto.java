package io.github.jdubois.bootui.core.dto;

import java.util.Map;

/**
 * A single captured outbound HTTP client call.
 *
 * <p>Populated by BootUI's {@code ClientHttpRequestInterceptor}/{@code ExchangeFilterFunction} bindings for
 * Spring's {@code RestClient}, {@code RestTemplate}, and {@code WebClient}. Query parameter values in {@link
 * #uri()} and header values in {@link #requestHeaders()} carry a name BootUI can check against {@code
 * SecretMasker}, so — unlike SQL bound parameters, which are only ever exposed at all when capture is
 * explicitly enabled — they are never withheld wholesale; instead each value is masked by name the same way
 * the Config and HTTP Exchanges panels do, honoring the live {@code bootui.expose-values} /
 * {@code bootui.mask-secrets} exposure policy at report time (so a runtime change to either is reflected
 * immediately for both already-captured and new calls).</p>
 *
 * @param id sequence number, increasing in call order
 * @param timestamp epoch milliseconds when the call completed (or failed)
 * @param method the HTTP method, e.g. {@code GET}
 * @param uri the full request URI with query parameter values displayed per the exposure policy
 * @param host the target host
 * @param path the request path, never including the query string
 * @param status the HTTP response status, or {@code null} when the call threw before a response arrived
 * @param durationMillis wall-clock call time in milliseconds
 * @param success whether the call completed without the client throwing (a 4xx/5xx response still counts
 *     as a successful call; only a connection failure, timeout, or similar client-side exception is not)
 * @param errorMessage the client-side failure message when {@code success} is {@code false}
 * @param slow whether the call exceeded the configured slow-call threshold
 * @param clientType which Spring HTTP client made the call: {@code RestClient}, {@code RestTemplate}, or
 *     {@code WebClient}
 * @param requestHeaders request header names to values displayed per the exposure policy; empty unless
 *     header capture is enabled
 * @param traceId Micrometer/W3C trace id active when the call ran, or {@code null} when no tracer was
 *     present; used to correlate the call to its originating request exactly
 * @param thread name of the thread that made the call
 * @param callSite the first application stack frame above the client call, formatted as {@code
 *     ClassName.methodName(File.java:42)}, or {@code null} when call-site capture is disabled or no
 *     application frame was found; never gated by value exposure since it names the application's own
 *     code, never a value
 */
public record RestClientTraceEntryDto(
        long id,
        long timestamp,
        String method,
        String uri,
        String host,
        String path,
        Integer status,
        long durationMillis,
        boolean success,
        String errorMessage,
        boolean slow,
        String clientType,
        Map<String, String> requestHeaders,
        String traceId,
        String thread,
        String callSite) {

    public RestClientTraceEntryDto {
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
    }
}
