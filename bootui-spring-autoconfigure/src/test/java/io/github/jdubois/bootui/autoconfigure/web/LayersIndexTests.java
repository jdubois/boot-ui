package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LayersIndexTests {

    @Test
    void anEntryBelongsToTheFirstLayerListingItAsSpringBootResolvesIt() {
        LayersIndex index = read("""
                - "dependencies":
                  - "BOOT-INF/lib/"
                - "application":
                  - "BOOT-INF/lib/users.jar"
                  - "BOOT-INF/classes/"
                """);

        assertThat(index.isApplication("BOOT-INF/lib/users.jar")).isFalse();
        assertThat(index.isApplication("BOOT-INF/classes/com/boosting/App.class"))
                .isTrue();
        assertThat(index.isApplication("BOOT-INF/lib-other/x.jar")).isFalse();
    }

    @Test
    void crlfLineEndingsAreAccepted() {
        LayersIndex index = read("- \"application\":\r\n  - \"BOOT-INF/lib/users.jar\"\r\n");

        assertThat(index.isApplication("BOOT-INF/lib/users.jar")).isTrue();
    }

    @Test
    void aCustomLayeringWithoutAnApplicationLayerSaysNothing() {
        LayersIndex index = read("""
                - "libraries":
                  - "BOOT-INF/lib/"
                """);

        assertThat(index.isApplication("BOOT-INF/lib/users.jar")).isNull();
    }

    @Test
    void malformedEmptyOrOversizedIndexesAreUnreadable() {
        assertThat(read("- \"application\":\n  - \"BOOT-INF/lib/users.jar\"\nnonsense\n"))
                .isSameAs(LayersIndex.UNREADABLE);
        assertThat(read("  - \"BOOT-INF/lib/users.jar\"\n")).isSameAs(LayersIndex.UNREADABLE);
        assertThat(read("\n\n")).isSameAs(LayersIndex.UNREADABLE);
        assertThat(LayersIndex.read(new ByteArrayInputStream(new byte[LayersIndex.MAX_BYTES + 1])))
                .isSameAs(LayersIndex.UNREADABLE);
        assertThat(read("- \"application\":\n  - \"BOOT-INF/\rlib/users.jar\"\n"))
                .isSameAs(LayersIndex.UNREADABLE);
        byte[] invalidUtf8 = {'-', ' ', '"', 'a', (byte) 0xC3, '"', ':', '\n'};
        assertThat(LayersIndex.read(new ByteArrayInputStream(invalidUtf8))).isSameAs(LayersIndex.UNREADABLE);
        assertThat(LayersIndex.UNREADABLE.isApplication("BOOT-INF/lib/users.jar"))
                .isFalse();
    }

    private static LayersIndex read(String index) {
        InputStream input = new ByteArrayInputStream(index.getBytes(StandardCharsets.UTF_8));
        return LayersIndex.read(input);
    }
}
