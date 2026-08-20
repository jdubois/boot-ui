package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CorrelationIdDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral extraction of correlation identifiers from the request headers an adapter already
 * captured for an inbound exchange, plus the exposure-aware mapping to the wire DTO.
 *
 * <p>This deliberately reuses the existing inbound request capture: every adapter already hands the engine
 * the request's header map when it assembles an HTTP exchange, so recognising correlation identifiers adds
 * no second filter, no request-body access, and no extra work on the request path.</p>
 *
 * <p>Everything the plan calls "one canonical policy" lives in {@link CorrelationIdPolicy}: case-insensitive
 * header-name matching, value normalization, the per-value length bound, and the per-request identifier cap
 * are applied here in one place so no adapter can drift.</p>
 */
public final class CorrelationIdExtractor {

    private CorrelationIdExtractor() {}

    /**
     * Reads the configured correlation identifiers out of one request's headers.
     *
     * <p>Header names match case-insensitively, and a name that appears more than once (in any casing)
     * contributes only its first non-blank value: a correlation identifier is one identity per name, so a
     * duplicated or repeated header cannot inflate the captured cardinality. Identifiers are returned in
     * the canonical header-name order of {@code settings} — not in arrival order — so the same request
     * renders identically on every adapter, and at most {@link CorrelationIdPolicy#MAX_IDS_PER_REQUEST}
     * of them are kept.</p>
     *
     * @param requestHeaders raw, multi-valued, case-preserving request headers, or {@code null}
     * @param settings the accepted header names, or {@code null} for {@link CorrelationIdSettings#defaults()}
     * @return the captured identifiers, newest policy order, never {@code null}
     */
    public static List<CapturedCorrelationId> extract(
            Map<String, List<String>> requestHeaders, CorrelationIdSettings settings) {
        if (requestHeaders == null || requestHeaders.isEmpty()) {
            return List.of();
        }
        CorrelationIdSettings effective = settings == null ? CorrelationIdSettings.defaults() : settings;
        if (effective.headerNames().isEmpty()) {
            return List.of();
        }
        List<CapturedCorrelationId> captured = new ArrayList<>();
        for (String name : effective.headerNames()) {
            if (captured.size() >= CorrelationIdPolicy.MAX_IDS_PER_REQUEST) {
                break;
            }
            CapturedCorrelationId identifier = capture(name, matchingHeaders(requestHeaders, name));
            if (identifier != null) {
                captured.add(identifier);
            }
        }
        return List.copyOf(captured);
    }

    /**
     * The header entries matching one accepted name, in a deterministic order.
     *
     * <p>This runs on the read path for every rendered exchange, so it walks the captured header map
     * directly instead of building a normalized copy of it: the accepted names are few and already
     * lower-case, and the overwhelmingly common case (no match, or exactly one) allocates nothing beyond
     * the returned singleton.</p>
     *
     * <p>When a request carries the same name in several casings, the adapters disagree on iteration
     * order — servlet, reactive and Vert.x header maps are all built differently — so the matches are
     * ordered by their raw name. Without that, {@code X-Request-ID: a} and {@code x-request-id: b} on one
     * request could capture a different identifier on each runtime.</p>
     */
    private static List<List<String>> matchingHeaders(Map<String, List<String>> requestHeaders, String name) {
        List<Map.Entry<String, List<String>>> matches = null;
        for (Map.Entry<String, List<String>> entry : requestHeaders.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null || !name.equalsIgnoreCase(key.trim())) {
                continue;
            }
            if (matches == null) {
                matches = new ArrayList<>(1);
            }
            matches.add(entry);
        }
        if (matches == null) {
            return List.of();
        }
        if (matches.size() > 1) {
            matches.sort(Comparator.comparing(Map.Entry::getKey));
        }
        return matches.stream().map(Map.Entry::getValue).toList();
    }

    private static CapturedCorrelationId capture(String name, List<List<String>> valueLists) {
        for (List<String> values : valueLists) {
            for (String value : values) {
                String normalized = CorrelationIdPolicy.normalizeValue(value);
                if (normalized == null) {
                    continue;
                }
                boolean truncated = CorrelationIdPolicy.isOverlong(normalized);
                String bounded = CorrelationIdPolicy.truncate(normalized);
                return new CapturedCorrelationId(name, bounded, truncated, CorrelationIdPolicy.lookupId(bounded));
            }
        }
        return null;
    }

    /**
     * Applies the live value-exposure policy to captured identifiers at response-assembly time.
     *
     * <p>A correlation identifier is application or gateway data rather than BootUI metadata, so it is
     * masked by default and revealed only under {@link ValueExposure#FULL}; under
     * {@link ValueExposure#METADATA_ONLY} both the value and its lookup identity are withheld and only
     * the name and the bounds flag remain. Withholding the identity there is deliberate: it is a
     * reproducible digest of the value, so serving it would hand out a value-derived datum in the one
     * mode that promises none, and it is what makes correlation filtering unavailable under
     * {@code METADATA_ONLY} rather than quietly weakened. Because this runs per response rather than at
     * capture time, changing {@code bootui.expose-values} takes effect immediately without restarting
     * capture.</p>
     *
     * @param captured identifiers from {@link #extract(Map, CorrelationIdSettings)}
     * @param exposure the live exposure policy, or {@code null} for the masked default
     */
    public static List<CorrelationIdDto> toDtos(List<CapturedCorrelationId> captured, ValueExposure exposure) {
        if (captured == null || captured.isEmpty()) {
            return List.of();
        }
        List<CorrelationIdDto> dtos = new ArrayList<>(captured.size());
        for (CapturedCorrelationId identifier : captured) {
            dtos.add(toDto(identifier, exposure));
        }
        return List.copyOf(dtos);
    }

    private static CorrelationIdDto toDto(CapturedCorrelationId identifier, ValueExposure exposure) {
        if (exposure == ValueExposure.FULL) {
            return new CorrelationIdDto(
                    identifier.name(), identifier.value(), false, identifier.truncated(), identifier.lookupId());
        }
        if (exposure == ValueExposure.METADATA_ONLY) {
            return new CorrelationIdDto(identifier.name(), null, true, identifier.truncated(), null);
        }
        return new CorrelationIdDto(
                identifier.name(), SecretMasker.MASKED_VALUE, true, identifier.truncated(), identifier.lookupId());
    }

    /** The opaque lookup identities of captured identifiers, in the same order. */
    public static List<String> lookupIds(List<CorrelationIdDto> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(identifiers.size());
        for (CorrelationIdDto identifier : identifiers) {
            if (identifier != null && identifier.lookupId() != null) {
                ids.add(identifier.lookupId());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * One correlation identifier as captured, before the exposure policy is applied.
     *
     * @param name normalized (lower-case) header name it was read from
     * @param value the normalized, length-bounded identifier — kept only long enough to render it under a
     *     permitting exposure policy, and never logged or used as a metric label
     * @param truncated whether the inbound identifier was longer than the per-value bound
     * @param lookupId the opaque, one-way lookup identity derived from {@code value}
     */
    public record CapturedCorrelationId(String name, String value, boolean truncated, String lookupId) {}
}
