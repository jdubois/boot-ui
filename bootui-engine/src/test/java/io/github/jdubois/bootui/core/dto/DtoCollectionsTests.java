package io.github.jdubois.bootui.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared defensive-copy helper behind every core DTO collection component.
 */
class DtoCollectionsTests {

    @Test
    void nullListBecomesEmptyImmutableList() {
        List<String> copy = DtoCollections.immutableCopy((List<String>) null);

        assertThat(copy).isEmpty();
        assertThatThrownBy(() -> copy.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullMapBecomesEmptyImmutableMap() {
        Map<String, String> copy = DtoCollections.immutableCopy((Map<String, String>) null);

        assertThat(copy).isEmpty();
        assertThatThrownBy(() -> copy.put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyCollectionsBecomeEmptyImmutableCollections() {
        List<String> list = DtoCollections.immutableCopy(new ArrayList<String>());
        Map<String, String> map = DtoCollections.immutableCopy(new LinkedHashMap<String, String>());

        assertThat(list).isEmpty();
        assertThat(map).isEmpty();
        assertThatThrownBy(() -> list.add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listCopyIsDetachedFromTheCallerList() {
        List<String> source = new ArrayList<>(List.of("a", "b"));

        List<String> copy = DtoCollections.immutableCopy(source);
        source.add("c");
        source.set(0, "mutated");

        assertThat(copy).containsExactly("a", "b");
    }

    @Test
    void mapCopyIsDetachedFromTheCallerMap() {
        Map<String, Integer> source = new LinkedHashMap<>();
        source.put("a", 1);

        Map<String, Integer> copy = DtoCollections.immutableCopy(source);
        source.put("b", 2);
        source.put("a", 99);

        assertThat(copy).containsExactly(Map.entry("a", 1));
    }

    @Test
    void returnedCollectionsRejectMutation() {
        List<String> list = DtoCollections.immutableCopy(new ArrayList<>(List.of("a")));
        Map<String, String> map = DtoCollections.immutableCopy(new LinkedHashMap<>(Map.of("k", "v")));

        assertThatThrownBy(() -> list.add("b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.remove(0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.set(0, "b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(list::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.put("k2", "v2")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.remove("k")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(map::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.keySet().remove("k")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.values().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.entrySet().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullElementsAreToleratedUnlikeListCopyOf() {
        List<String> source = new ArrayList<>(Arrays.asList("a", null));
        Map<String, String> mapSource = new LinkedHashMap<>();
        mapSource.put("present", "value");
        mapSource.put("absent", null);

        assertThat(DtoCollections.immutableCopy(source)).containsExactly("a", null);
        assertThat(DtoCollections.immutableCopy(mapSource))
                .containsOnlyKeys("present", "absent")
                .containsEntry("present", "value")
                .containsEntry("absent", null);
    }

    @Test
    void mapCopyKeepsCallerIterationOrderSoSerializedKeyOrderIsStable() {
        Map<String, Integer> source = new LinkedHashMap<>();
        source.put("zeta", 1);
        source.put("alpha", 2);
        source.put("mid", 3);

        assertThat(DtoCollections.immutableCopy(source).keySet()).containsExactly("zeta", "alpha", "mid");
    }

    @Test
    void copiesKeepValueSemanticsForEqualsAndHashCode() {
        List<String> list = DtoCollections.immutableCopy(new ArrayList<>(List.of("a", "b")));
        Map<String, Integer> map = DtoCollections.immutableCopy(new LinkedHashMap<>(Map.of("k", 1)));

        assertThat(list).isEqualTo(List.of("a", "b"));
        assertThat(list).hasSameHashCodeAs(List.of("a", "b"));
        assertThat(map).isEqualTo(Map.of("k", 1));
        assertThat(map).hasSameHashCodeAs(Map.of("k", 1));
    }
}
