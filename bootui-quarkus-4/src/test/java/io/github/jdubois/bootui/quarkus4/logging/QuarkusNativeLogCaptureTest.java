package io.github.jdubois.bootui.quarkus4.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class QuarkusNativeLogCaptureTest {

    @Test
    void denialInstallsNoHandlerAndStandaloneCallbacksRemainInert() {
        Instance<LogTailBuffer> buffers = instance();
        Instance<RuntimeJournal> journals = instance();
        Instance<QuarkusLogCoverage> coverage = instance();
        LogTailBuffer buffer = mock(LogTailBuffer.class);
        RuntimeJournal journal = mock(RuntimeJournal.class);
        var before = Logger.getLogger("").getHandlers();

        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            QuarkusLogTailCapture capture = new QuarkusLogTailCapture(buffers, journals, coverage);
            capture.onStart(null);
            capture.onStop(null);
            QuarkusLogTailHandler handler =
                    new QuarkusLogTailHandler(buffer, new InternalPackageMatcher(List.of()), journal);
            handler.publish(new LogRecord(Level.SEVERE, "host failure"));

            assertThat(Logger.getLogger("").getHandlers()).containsExactly(before);
            verifyNoInteractions(buffers, journals, coverage, buffer, journal);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance() {
        return mock(Instance.class);
    }
}
