package io.github.jdubois.bootui.core.dto;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defensive-copy helpers shared by the core DTO records.
 *
 * <p>Core DTOs are documented as immutable, so every collection component is copied in the record's
 * compact constructor. Without the copy a caller could keep a reference to the collection it passed
 * in, or mutate the collection returned by an accessor, and change a report after it was published.</p>
 *
 * <p>The copies deliberately do not use {@link List#copyOf(java.util.Collection)} or
 * {@link Map#copyOf(Map)}:</p>
 *
 * <ul>
 *   <li>{@code Map.copyOf} produces a hash-ordered map whose iteration order is not the insertion
 *       order and is randomized per JVM run, which would make the serialized JSON key order unstable
 *       and different between Jackson 2 and Jackson 3 runs. A {@link LinkedHashMap} copy keeps the
 *       caller's order, so the emitted bytes stay identical across both Jackson generations.</li>
 *   <li>Both {@code copyOf} methods reject {@code null} elements, values, and keys. Panel data is
 *       assembled from JVM and framework sources that can legitimately yield a {@code null} element,
 *       and a DTO constructor is the wrong place to turn that into a request failure.</li>
 * </ul>
 *
 * <p>{@code null} is normalized to an empty collection: no core DTO documents a nullable collection
 * component, and the existing DTOs already used that convention.</p>
 *
 * <p>The copies are deep with respect to JSON-shaped payloads. Typed components hold immutable DTO
 * records, boxed scalars, or strings, so element copying is a no-op for them, but the DTO surface
 * also exposes a few {@code Object}-typed components ({@code ConfigPropertyDto.value},
 * {@code HealthNodeDto.details}, {@code SpanAttributeDto.value}, and the values of
 * {@code DevServiceDto.connectionDetails}) that receive framework-supplied maps, collections, and
 * arrays. Those are snapshotted recursively through {@link #immutableValue(Object)}; a nested
 * payload is otherwise still caller-owned and can change a published report.</p>
 */
final class DtoCollections {

    private DtoCollections() {}

    /**
     * Returns an unmodifiable copy of {@code values}, preserving iteration order.
     *
     * @param values the caller-owned list, possibly {@code null}
     * @param <T> the element type
     * @return an immutable list, empty when {@code values} is {@code null} or empty
     */
    @SuppressWarnings("unchecked")
    static <T> List<T> immutableCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add((T) immutableValue(value));
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * Returns an immutable copy of {@code locations} aligned index-for-index with {@code texts}, or an empty
     * list when no entry carries a location. A {@code null} element means that one text has no location.
     *
     * @throws IllegalArgumentException when a non-empty location list does not match the text list's size
     */
    static <T> List<T> alignedCopy(List<?> texts, List<T> locations) {
        List<T> copy = immutableCopy(locations);
        if (copy.isEmpty() || copy.stream().allMatch(java.util.Objects::isNull)) {
            return List.of();
        }
        if (copy.size() != (texts == null ? 0 : texts.size())) {
            throw new IllegalArgumentException("Locations must align index-for-index with their violations.");
        }
        return copy;
    }

    /**
     * Returns an unmodifiable copy of {@code values}, preserving iteration order.
     *
     * @param values the caller-owned map, possibly {@code null}
     * @param <K> the key type
     * @param <V> the value type
     * @return an immutable map, empty when {@code values} is {@code null} or empty
     */
    @SuppressWarnings("unchecked")
    static <K, V> Map<K, V> immutableCopy(Map<K, V> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<K, V> copy = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : values.entrySet()) {
            copy.put(entry.getKey(), (V) immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns an immutable snapshot of a loosely typed, JSON-shaped value.
     *
     * <p>Maps, collections, and arrays are copied recursively; a collection or array becomes an
     * unmodifiable list, which Jackson serializes to the same JSON array as the original. Anything
     * else is returned unchanged, because the remaining values BootUI puts behind an {@code Object}
     * component are strings, boxed scalars, and immutable DTO records.</p>
     *
     * @param value the caller-owned value, possibly {@code null}
     * @return an immutable equivalent of {@code value}
     */
    static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(entry.getKey(), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> values) {
            List<Object> copy = new ArrayList<>(values.size());
            for (Object element : values) {
                copy.add(immutableValue(element));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                copy.add(immutableValue(Array.get(value, i)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
