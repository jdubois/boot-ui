package io.github.jdubois.bootui.engine.httpclient;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.HttpClientCallLinkDto;
import io.github.jdubois.bootui.core.dto.HttpClientDto;
import io.github.jdubois.bootui.core.dto.HttpClientRegistryReport;
import io.github.jdubois.bootui.core.dto.HttpClientSettingDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceGroupDto;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.spi.DiscoveredHttpClient;
import io.github.jdubois.bootui.spi.DiscoveredHttpClientSetting;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.HttpClientProvider;
import io.github.jdubois.bootui.spi.HttpClientVocabulary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral logic behind the HTTP Clients panel, shared by the Spring Boot and Quarkus adapters.
 *
 * <p>It reads raw registrations from an (optional) {@link HttpClientProvider} and owns everything that must
 * look identical on every runtime: safe URL handling, provenance presentation, stable identity and
 * ordering, masking of settings, and the REST Client trace cross-link.</p>
 *
 * <p>Assembling this report performs no network call, resolves no name, and never instantiates a client:
 * the provider is contractually restricted to reading registrations and configuration. The service is
 * recomputed per request so live configuration and value-exposure changes are reflected immediately.</p>
 */
public final class HttpClientRegistryService {

    /** Upper bound on the retained calls linked to any one client, so a busy host cannot bloat a row. */
    static final int MAX_LINKED_CALLS_PER_CLIENT = 5;

    private static final String NO_PROVIDER_REASON =
            "Not available: this runtime has no declarative HTTP client registry wired.";
    private static final String TRACE_UNAVAILABLE_REASON =
            "REST Client trace has not instrumented a client yet, so no observed calls can be attributed.";
    private static final String TRACE_ABSENT_REASON = "REST Client trace is not available on this runtime.";

    private static final SecretMasker MASKER = new SecretMasker();

    /** Declarative clients first, framework-managed builders last; within a rank, by name. */
    private static final List<String> KIND_ORDER = List.of(
            HttpClientVocabulary.KIND_HTTP_INTERFACE,
            HttpClientVocabulary.KIND_MICROPROFILE_REST_CLIENT,
            HttpClientVocabulary.KIND_OPEN_FEIGN,
            HttpClientVocabulary.KIND_REST_CLIENT_BUILDER,
            HttpClientVocabulary.KIND_WEB_CLIENT_BUILDER);

    private static final Map<String, String> KIND_LABELS = Map.of(
            HttpClientVocabulary.KIND_HTTP_INTERFACE, "HTTP Interface",
            HttpClientVocabulary.KIND_MICROPROFILE_REST_CLIENT, "REST Client interface",
            HttpClientVocabulary.KIND_OPEN_FEIGN, "OpenFeign client",
            HttpClientVocabulary.KIND_REST_CLIENT_BUILDER, "RestClient builder",
            HttpClientVocabulary.KIND_WEB_CLIENT_BUILDER, "WebClient builder");

    private static final Map<String, String> KIND_FRAMEWORKS = Map.of(
            HttpClientVocabulary.KIND_HTTP_INTERFACE, "Spring HTTP Interface",
            HttpClientVocabulary.KIND_MICROPROFILE_REST_CLIENT, "MicroProfile REST Client",
            HttpClientVocabulary.KIND_OPEN_FEIGN, "Spring Cloud OpenFeign",
            HttpClientVocabulary.KIND_REST_CLIENT_BUILDER, "Spring RestClient",
            HttpClientVocabulary.KIND_WEB_CLIENT_BUILDER, "Spring WebClient");

    private final HttpClientProvider provider;
    private final ExposurePolicy exposurePolicy;
    private final RestClientTraceRecorder traceRecorder;

    public HttpClientRegistryService(
            HttpClientProvider provider, ExposurePolicy exposurePolicy, RestClientTraceRecorder traceRecorder) {
        this.provider = provider;
        this.exposurePolicy = exposurePolicy;
        this.traceRecorder = traceRecorder;
    }

    /**
     * Whether the panel has anything to show. Used by each adapter's panel manifest so an application with
     * no supported HTTP client technology reports the panel unavailable instead of an empty table.
     */
    public boolean available() {
        return provider != null && provider.available();
    }

    /** The framework-correct hint shown when {@link #available()} is {@code false}. */
    public String unavailableReason() {
        if (provider == null) {
            return NO_PROVIDER_REASON;
        }
        String reason = HttpClientUrls.blankToNull(provider.unavailableReason());
        return reason == null ? NO_PROVIDER_REASON : reason;
    }

    /** The sanitized, ordered and cross-linked HTTP client registry report. */
    public HttpClientRegistryReport report() {
        ValueExposure exposure = exposure();
        if (provider == null) {
            return HttpClientRegistryReport.unavailable(unavailableReason(), exposure.name());
        }

        // One discovery pass per report: on Spring this walks every bean definition, so calling available()
        // first would double the cost of every request for an answer the discovered list already gives.
        List<DiscoveredHttpClient> declared = provider.clients();
        List<DiscoveredHttpClient> discovered = declared == null ? new ArrayList<>() : new ArrayList<>(declared);
        discovered.removeIf(client -> client == null || HttpClientUrls.blankToNull(client.name()) == null);
        if (discovered.isEmpty()) {
            return HttpClientRegistryReport.unavailable(unavailableReason(), exposure.name());
        }
        discovered.sort(Comparator.comparingInt(HttpClientRegistryService::kindRank)
                .thenComparing(client -> client.name().toLowerCase(Locale.ROOT))
                .thenComparing(
                        DiscoveredHttpClient::declaredInterface, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> hosts = discovered.stream()
                .map(client -> HttpClientUrls.host(client.resolvedBaseUrl()))
                .toList();
        Set<String> ambiguousHosts = ambiguousHosts(hosts);

        TraceEvidence evidence = traceEvidence(exposure);

        Set<String> usedIds = new HashSet<>();
        List<HttpClientDto> clients = new ArrayList<>(discovered.size());
        int unresolved = 0;
        for (int index = 0; index < discovered.size(); index++) {
            DiscoveredHttpClient client = discovered.get(index);
            String host = hosts.get(index);
            HttpClientDto dto = toDto(client, host, ambiguousHosts, evidence, exposure, usedIds);
            if (HttpClientVocabulary.BASE_URL_UNRESOLVED.equals(dto.baseUrlStatus())) {
                unresolved++;
            }
            clients.add(dto);
        }

        return new HttpClientRegistryReport(
                true,
                null,
                clients.size(),
                exposure.name(),
                evidence.available(),
                evidence.unavailableReason(),
                clients,
                warnings(unresolved, ambiguousHosts.size()));
    }

    private HttpClientDto toDto(
            DiscoveredHttpClient client,
            String host,
            Set<String> ambiguousHosts,
            TraceEvidence evidence,
            ValueExposure exposure,
            Set<String> usedIds) {

        String configured = HttpClientUrls.sanitize(client.configuredBaseUrl(), exposure);
        String rawResolved = HttpClientUrls.blankToNull(client.resolvedBaseUrl());
        boolean resolved = HttpClientUrls.isResolvedAbsoluteUrl(rawResolved);
        String resolvedDisplay = resolved ? HttpClientUrls.sanitize(rawResolved, exposure) : null;

        String baseUrlStatus;
        if (configured == null && rawResolved == null) {
            baseUrlStatus = HttpClientVocabulary.BASE_URL_NOT_DECLARED;
        } else if (resolved) {
            baseUrlStatus = HttpClientVocabulary.BASE_URL_RESOLVED;
        } else {
            baseUrlStatus = HttpClientVocabulary.BASE_URL_UNRESOLVED;
        }

        String baseUrlProvenance = HttpClientVocabulary.BASE_URL_NOT_DECLARED.equals(baseUrlStatus)
                ? HttpClientVocabulary.PROVENANCE_UNAVAILABLE
                : provenanceOrUnavailable(client.baseUrlProvenance());

        List<HttpClientSettingDto> settings = settings(client, exposure);

        List<HttpClientCallLinkDto> links = List.of();
        String observedStatus;
        if (!evidence.available()) {
            observedStatus = HttpClientVocabulary.OBSERVED_UNAVAILABLE;
        } else if (!resolved || host == null || ambiguousHosts.contains(host)) {
            observedStatus = HttpClientVocabulary.OBSERVED_NOT_ATTRIBUTABLE;
        } else {
            links = evidence.linksFor(host);
            observedStatus =
                    links.isEmpty() ? HttpClientVocabulary.OBSERVED_NO_CALLS : HttpClientVocabulary.OBSERVED_LINKED;
        }

        return new HttpClientDto(
                uniqueId(client, usedIds),
                client.name().trim(),
                kindOrUnknown(client.kind()),
                KIND_LABELS.getOrDefault(client.kind(), "HTTP client"),
                KIND_FRAMEWORKS.getOrDefault(client.kind(), "Unknown"),
                HttpClientUrls.blankToNull(client.declaredInterface()),
                HttpClientUrls.blankToNull(client.configKey()),
                configured,
                resolvedDisplay,
                baseUrlStatus,
                baseUrlProvenance,
                HttpClientVocabulary.PROVENANCE_UNAVAILABLE.equals(baseUrlProvenance)
                        ? null
                        : HttpClientUrls.blankToNull(client.baseUrlSource()),
                settings,
                links,
                observedStatus);
    }

    /**
     * Masks and normalizes the adapter's raw settings. A blank value is folded into the explicit
     * "unavailable" shape so the browser never has to distinguish {@code null} from {@code ""}, and every
     * value is name-masked so a provider mistake cannot leak a credential through this panel.
     */
    private List<HttpClientSettingDto> settings(DiscoveredHttpClient client, ValueExposure exposure) {
        List<HttpClientSettingDto> settings = new ArrayList<>();
        for (DiscoveredHttpClientSetting setting : client.settings()) {
            if (setting == null || HttpClientUrls.blankToNull(setting.name()) == null) {
                continue;
            }
            String name = setting.name().trim();
            String value = HttpClientUrls.blankToNull(setting.value());
            if (value == null) {
                settings.add(new HttpClientSettingDto(
                        categoryOrTransport(setting.category()),
                        name,
                        null,
                        HttpClientVocabulary.PROVENANCE_UNAVAILABLE,
                        null));
                continue;
            }
            settings.add(new HttpClientSettingDto(
                    categoryOrTransport(setting.category()),
                    name,
                    displayValue(setting, name, value, exposure),
                    provenanceOrUnavailable(setting.provenance()),
                    HttpClientUrls.blankToNull(setting.source())));
        }
        return List.copyOf(settings);
    }

    private String displayValue(
            DiscoveredHttpClientSetting setting, String name, String value, ValueExposure exposure) {
        if (exposure == ValueExposure.METADATA_ONLY) {
            // Same contract as every other panel: metadata-only shows that a setting exists and where it
            // came from, never what it says.
            return null;
        }
        if (HttpClientVocabulary.CATEGORY_PROXY.equals(setting.category())
                || HttpClientVocabulary.CATEGORY_TLS.equals(setting.category())
                || value.contains("://")) {
            // Proxy and TLS settings are URL-like or endpoint-like: strip user info before anything else.
            value = HttpClientUrls.sanitize(value, exposure);
        }
        String candidate = HttpClientUrls.truncate(value);
        // Defence in depth: mask by the setting name and by the last segment of the source key, so a provider
        // that reports a credential-bearing property can never turn this panel into a secret disclosure. Only
        // the last segment is used, because a client named `api-key-service` would otherwise make its own
        // connect timeout look like a secret.
        Object masked = MASKER.mask(name, candidate);
        String sourceKey = lastSegment(HttpClientUrls.blankToNull(setting.source()));
        if (sourceKey != null) {
            masked = MASKER.mask(sourceKey, masked);
        }
        return String.valueOf(masked);
    }

    /** The final {@code .} or {@code /} separated segment of a configuration key. */
    private static String lastSegment(String key) {
        if (key == null) {
            return null;
        }
        int separator = Math.max(key.lastIndexOf('.'), key.lastIndexOf('/'));
        String segment = separator < 0 ? key : key.substring(separator + 1);
        return segment.isBlank() ? null : segment;
    }

    private TraceEvidence traceEvidence(ValueExposure exposure) {
        if (traceRecorder == null) {
            return TraceEvidence.unavailable(TRACE_ABSENT_REASON);
        }
        if (!traceRecorder.isEnabled() || !traceRecorder.hasInstrumentedClient()) {
            return TraceEvidence.unavailable(TRACE_UNAVAILABLE_REASON);
        }
        Map<String, List<HttpClientCallLinkDto>> byHost = new HashMap<>();
        boolean maskSecrets = exposurePolicy == null || exposurePolicy.maskSecrets();
        for (RestClientTraceGroupDto group : traceRecorder.topCalls(maskSecrets, exposure)) {
            String host = HttpClientUrls.blankToNull(group.host());
            if (host == null) {
                continue;
            }
            byHost.computeIfAbsent(host.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .add(new HttpClientCallLinkDto(
                            group.method(), group.path(), group.executions(), group.maxDurationMillis()));
        }
        return new TraceEvidence(true, null, byHost);
    }

    /** Hosts claimed by more than one registered client: those clients stay unlinked rather than guessed. */
    private static Set<String> ambiguousHosts(List<String> hosts) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String host : hosts) {
            if (host != null) {
                counts.merge(host, 1, Integer::sum);
            }
        }
        Set<String> ambiguous = new LinkedHashSet<>();
        counts.forEach((host, count) -> {
            if (count > 1) {
                ambiguous.add(host);
            }
        });
        return ambiguous;
    }

    private static List<String> warnings(int unresolved, int ambiguousHosts) {
        List<String> warnings = new ArrayList<>();
        if (unresolved > 0) {
            warnings.add(unresolved
                    + (unresolved == 1 ? " client has" : " clients have")
                    + " a base URL that could not be resolved. Check for unresolved property placeholders.");
        }
        if (ambiguousHosts > 0) {
            warnings.add(ambiguousHosts
                    + (ambiguousHosts == 1 ? " host is" : " hosts are")
                    + " shared by more than one client, so observed calls are not attributed to them.");
        }
        return warnings;
    }

    private static String uniqueId(DiscoveredHttpClient client, Set<String> usedIds) {
        String base = kindOrUnknown(client.kind()).toLowerCase(Locale.ROOT) + ":"
                + client.name().trim();
        String candidate = base;
        int suffix = 2;
        while (!usedIds.add(candidate)) {
            candidate = base + "#" + suffix++;
        }
        return candidate;
    }

    private static int kindRank(DiscoveredHttpClient client) {
        int rank = KIND_ORDER.indexOf(client.kind());
        return rank < 0 ? KIND_ORDER.size() : rank;
    }

    private static String kindOrUnknown(String kind) {
        return KIND_ORDER.contains(kind) ? kind : "UNKNOWN";
    }

    private static String categoryOrTransport(String category) {
        return category == null || category.isBlank() ? HttpClientVocabulary.CATEGORY_TRANSPORT : category;
    }

    private static String provenanceOrUnavailable(String provenance) {
        return switch (provenance == null ? "" : provenance) {
            case HttpClientVocabulary.PROVENANCE_CLIENT,
                    HttpClientVocabulary.PROVENANCE_ANNOTATION,
                    HttpClientVocabulary.PROVENANCE_APPLICATION,
                    HttpClientVocabulary.PROVENANCE_FRAMEWORK -> provenance;
            default -> HttpClientVocabulary.PROVENANCE_UNAVAILABLE;
        };
    }

    private ValueExposure exposure() {
        if (exposurePolicy == null) {
            return ValueExposure.MASKED;
        }
        ValueExposure exposure = exposurePolicy.valueExposure();
        return exposure == null ? ValueExposure.MASKED : exposure;
    }

    private record TraceEvidence(
            boolean available, String unavailableReason, Map<String, List<HttpClientCallLinkDto>> byHost) {

        static TraceEvidence unavailable(String reason) {
            return new TraceEvidence(false, reason, Map.of());
        }

        List<HttpClientCallLinkDto> linksFor(String host) {
            List<HttpClientCallLinkDto> links = byHost.get(host);
            if (links == null || links.isEmpty()) {
                return List.of();
            }
            return links.stream()
                    .sorted(Comparator.comparingLong(HttpClientCallLinkDto::executions)
                            .reversed()
                            .thenComparing(HttpClientCallLinkDto::path, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(
                                    HttpClientCallLinkDto::method, Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(MAX_LINKED_CALLS_PER_CLIENT)
                    .toList();
        }
    }
}
