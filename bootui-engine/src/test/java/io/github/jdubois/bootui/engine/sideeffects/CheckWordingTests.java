package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The JDK check rows' sentences (M5-6b2): facts, a library frame named once, never as a weakness. */
class CheckWordingTests {

    @Test
    void aLibrarysReadWithNoApplicationFrameNamesItsFrameOnce() {
        String sentence = CheckWording.detail(
                SideEffectsCatalog.DESERIALIZATION,
                "com.example.Cart",
                "org.acme.Codec#decode",
                SideEffectOrigins.LIBRARY,
                "org.acme.Codec#decode",
                List.of(),
                false);

        assertThat(sentence)
                .startsWith("Deserialization without an ObjectInputFilter by library code `org.acme.Codec#decode`"
                        + " (classes read: com.example.Cart).")
                .doesNotContain(" at `");
    }

    @Test
    void aLibrarysReadForAnApplicationFrameNamesBoth() {
        assertThat(CheckWording.detail(
                        SideEffectsCatalog.DESERIALIZATION,
                        "com.example.Cart",
                        "com.example.CartService#load",
                        SideEffectOrigins.LIBRARY,
                        "org.acme.Codec#decode",
                        List.of("java.util.ArrayList"),
                        true))
                .startsWith("Deserialization without an ObjectInputFilter by library code `org.acme.Codec#decode` at"
                        + " `com.example.CartService#load` (classes read: com.example.Cart, java.util.ArrayList, and"
                        + " more).");
    }

    @Test
    void aLibraryRowWhoseFrameWasNotKeptIsStillWordedAsALibrarys() {
        assertThat(CheckWording.detail(
                        SideEffectsCatalog.WEAK_DIGEST, "MD5", null, SideEffectOrigins.LIBRARY, null, List.of(), false))
                .startsWith("Weak algorithm MD5 requested by library code (frame not kept) (no application frame on"
                        + " the stack).")
                .doesNotContainIgnoringCase("vulnerab");
    }
}
