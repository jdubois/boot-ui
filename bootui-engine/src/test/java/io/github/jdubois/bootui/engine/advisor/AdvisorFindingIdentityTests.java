package io.github.jdubois.bootui.engine.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdvisorFindingIdentityTests {

    @Test
    void joinsVersionedPartsWithBars() {
        assertThat(AdvisorFindingIdentity.of("v1", "unit=main", "entity=com.acme.Order", "member=lines"))
                .isEqualTo("v1:unit=main|entity=com.acme.Order|member=lines");
    }

    @Test
    void percentEncodesEveryCharacterOutsideTheSafeSet() {
        String identity =
                AdvisorFindingIdentity.of("v1", "a|b", "100%", "x y#z", "\"q\"", "caf\u00e9", "a$b/c-d_e.f:g=h");
        assertThat(identity).isEqualTo("v1:a%7Cb|100%25|x%20y%23z|%22q%22|caf%C3%A9|a$b/c-d_e.f:g=h");
        assertThat(AdvisorFindingIdentity.VALID.matcher(identity).matches()).isTrue();
    }

    @Test
    void anEmbeddedBarCannotAliasTwoParts() {
        assertThat(AdvisorFindingIdentity.of("v1", "a|b", "c"))
                .isNotEqualTo(AdvisorFindingIdentity.of("v1", "a", "b|c"));
    }

    @Test
    void aNullPartCountsAsEmpty() {
        assertThat(AdvisorFindingIdentity.of("v1", "a", null)).isEqualTo("v1:a|");
    }

    @Test
    void anOverlongKeyKeepsItsPrefixAndEndsWithAHashOfTheFullKey() {
        String longPart = "x".repeat(400);
        String one = AdvisorFindingIdentity.of("v1", "unit=main", longPart + "1");
        String two = AdvisorFindingIdentity.of("v1", "unit=main", longPart + "2");
        assertThat(one).hasSize(197);
        assertThat(one).startsWith("v1:unit=main|xxxx").matches("[^~]{180}~[0-9a-f]{16}");
        assertThat(one).isNotEqualTo(two);
        assertThat(AdvisorFindingIdentity.VALID.matcher(one).matches()).isTrue();
        assertThat(one).isEqualTo(AdvisorFindingIdentity.of("v1", "unit=main", longPart + "1"));
    }

    @Test
    void everyResultStaysWithinTheDocumentedCharsetAndLength() {
        for (String part : new String[] {"", "\u0000", "\ud83d\ude00", "a".repeat(5000), "|||", "~"}) {
            String identity = AdvisorFindingIdentity.of("v1", part, part);
            assertThat(identity).hasSizeLessThanOrEqualTo(AdvisorFindingIdentity.MAX_LENGTH);
            assertThat(AdvisorFindingIdentity.VALID.matcher(identity).matches())
                    .as(identity)
                    .isTrue();
        }
    }

    @Test
    void rejectsAnInvalidVersionOrNoParts() {
        assertThatThrownBy(() -> AdvisorFindingIdentity.of("1", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdvisorFindingIdentity.of(null, "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdvisorFindingIdentity.of("v1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesTheVersionAndTheScopeOfAnIdentity() {
        String identity = AdvisorFindingIdentity.of("v2", "ds=main db", "table=t");
        assertThat(AdvisorFindingIdentity.version(identity)).isEqualTo("v2");
        assertThat(AdvisorFindingIdentity.scope(identity)).isEqualTo(AdvisorFindingIdentity.encode("ds=main db"));
        assertThat(AdvisorFindingIdentity.version(null)).isNull();
        assertThat(AdvisorFindingIdentity.scope(null)).isNull();
    }
}
