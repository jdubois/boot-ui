package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodePathFragmentTests {

    @Test
    void decodesTheBridgesLayout() {
        CodePathFragment fragment = Blobs.handoff(7L, 0xabL, 0xcdL)
                .between(100L, 900L)
                .dropped(3L)
                .flags(CodePathFragment.FLAG_CUT)
                .node(-1, 4, 2, 1L, 800L, 300L)
                .node(0, 5, 2, 2L, 300L, 0L)
                .fragment();

        assertThat(fragment.generation()).isEqualTo(7L);
        assertThat(fragment.request()).isEqualTo(0xabL);
        assertThat(fragment.execution()).isEqualTo(0xcdL);
        assertThat(fragment.handoff()).isTrue();
        assertThat(fragment.flags() & CodePathFragment.FLAG_CUT).isNotZero();
        assertThat(fragment.durationNanos()).isEqualTo(800L);
        assertThat(fragment.dropped()).isEqualTo(3L);
        assertThat(fragment.parent()).containsExactly(-1, 0);
        assertThat(fragment.method()).containsExactly(4, 5);
        assertThat(fragment.calls()).containsExactly(1L, 2L);
        assertThat(CodePathFragment.hex(0xabL)).isEqualTo("00000000000000ab");
    }

    @Test
    void refusesMalformedBlobs() {
        assertThat(CodePathFragment.decode(null)).isNull();
        assertThat(CodePathFragment.decode(new long[3])).isNull();
        long[] wrongVersion = Blobs.request(1L, 1L).node(-1, 1, 0, 1L, 1L, 0L).blob();
        wrongVersion[CodePathFragment.H_VERSION] = 99L;
        assertThat(CodePathFragment.decode(wrongVersion)).isNull();
        long[] wrongLength = Blobs.request(1L, 1L).node(-1, 1, 0, 1L, 1L, 0L).blob();
        wrongLength[CodePathFragment.H_NODES] = 2L;
        assertThat(CodePathFragment.decode(wrongLength)).isNull();
        assertThat(Blobs.request(1L, 1L).node(0, 1, 0, 1L, 1L, 0L).fragment())
                .as("a node that is its own parent")
                .isNull();
        assertThat(Blobs.request(1L, 1L).node(-1, 1, 0, -1L, 1L, 0L).fragment())
                .as("negative calls")
                .isNull();
        assertThat(Blobs.request(1L, 1L).node(-1, 1, 7, 1L, 1L, 0L).fragment())
                .as("an unknown phase")
                .isNull();
    }
}
