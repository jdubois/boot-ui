package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BootUiJdbcCaptureGuardTests {

    @Test
    void isNotSuppressedByDefault() {
        assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isFalse();
    }

    @Test
    void runSuppressedSetsFlagDuringBlockAndRestoresAfter() {
        boolean[] observedDuring = new boolean[1];
        BootUiJdbcCaptureGuard.runSuppressed(() -> {
            observedDuring[0] = BootUiJdbcCaptureGuard.isSuppressed();
        });

        assertThat(observedDuring[0]).isTrue();
        assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isFalse();
    }

    @Test
    void restoresPriorStateEvenWhenActionThrows() {
        assertThatThrownBy(() -> BootUiJdbcCaptureGuard.runSuppressed((Runnable) () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isFalse();
    }

    @Test
    void nestedRunSuppressedRestoresOuterState() {
        BootUiJdbcCaptureGuard.runSuppressed(() -> {
            assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isTrue();
            BootUiJdbcCaptureGuard.runSuppressed(() -> {
                assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isTrue();
            });
            // Still suppressed after the nested (inner) block ends, since the outer block is still active.
            assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isTrue();
        });
        assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isFalse();
    }

    @Test
    void checkedVariantReturnsValueAndRestoresState() throws Exception {
        String result = BootUiJdbcCaptureGuard.runSuppressed(() -> {
            assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isTrue();
            return "value";
        });
        assertThat(result).isEqualTo("value");
        assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isFalse();
    }

    @Test
    void checkedVariantPropagatesCheckedExceptionAndRestoresState() {
        assertThatThrownBy(() -> BootUiJdbcCaptureGuard.runSuppressed(() -> {
                    throw new java.io.IOException("boom");
                }))
                .isInstanceOf(java.io.IOException.class);
        assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isFalse();
    }

    @Test
    void doesNotSuppressOtherThreads() throws InterruptedException {
        boolean[] observedOnOtherThread = new boolean[1];
        BootUiJdbcCaptureGuard.runSuppressed(() -> {
            Thread other = new Thread(() -> observedOnOtherThread[0] = BootUiJdbcCaptureGuard.isSuppressed());
            other.start();
            try {
                other.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(observedOnOtherThread[0]).isFalse();
    }
}
