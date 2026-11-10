package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentThreadLocalsTests {

    @AfterEach
    void reset() {
        AgentThreadLocals.rebind();
    }

    @Test
    void namingAHoldersKeyIsInertWithoutAnAgent() {
        AgentThreadLocals.bind(null);
        assertThatCode(() -> AgentThreadLocals.nameHolder(new ThreadLocal<>(), "example.Holder.KEY"))
                .doesNotThrowAnyException();
        assertThat(AgentThreadLocals.bound()).isFalse();
    }

    @Test
    void optionalNamingDoesNotUnbindAnOlderAgentsExistingScopeAndHolderMethods() {
        AgentThreadLocals.bind(LegacyThreadLocals.class);
        AgentThreadLocals.nameHolder(new ThreadLocal<>(), "example.Holder.KEY");

        assertThat(AgentThreadLocals.bound()).isTrue();
        assertThat(AgentThreadLocals.open()).isEqualTo(42L);
        assertThat(AgentThreadLocals.openUnowned()).isEqualTo(43L);
        assertThat(AgentThreadLocals.holder(1, 1, 1, null, null, 1L)[0]).isEqualTo("example.Legacy.KEY");
    }

    public static final class LegacyThreadLocals {

        public static void configure(boolean scopes) {}

        public static long open() {
            return 42L;
        }

        public static long openUnowned() {
            return 43L;
        }

        public static void own() {}

        public static void close(long token) {}

        public static void exclude(long generation, int id, int hash) {}

        public static String[] holder(
                long generation, int id, int hash, String[] packages, String[] holders, long budget) {
            return new String[] {"example.Legacy.KEY", "false", "true", null};
        }
    }
}
