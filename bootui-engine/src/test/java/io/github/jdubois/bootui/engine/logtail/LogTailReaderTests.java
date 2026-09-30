package io.github.jdubois.bootui.engine.logtail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LogTailReaderTests {

    private static final String SECRET_MESSAGE = "connecting with password=hunter2\nretrying with token: tok-42";

    private final AtomicReference<ValueExposure> mode = new AtomicReference<>(ValueExposure.MASKED);

    private final AtomicBoolean maskSecrets = new AtomicBoolean(true);

    private final ExposurePolicy policy = new ExposurePolicy() {
        @Override
        public ValueExposure valueExposure() {
            return mode.get();
        }

        @Override
        public boolean maskSecrets() {
            return maskSecrets.get();
        }
    };

    @Test
    void masksSecretAssignmentsInEveryLineOfASnapshotByDefault() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line(SECRET_MESSAGE));
        LogTailReader reader = new LogTailReader(buffer, policy);

        LogLineDto read = reader.recent().get(0);

        assertThat(read.message())
                .isEqualTo("connecting with password=******\nretrying with token: ******")
                .doesNotContain("hunter2", "tok-42");
        assertThat(read.messageOmitted()).isFalse();
        assertThat(read.timestamp()).isEqualTo(42L);
        assertThat(read.level()).isEqualTo("WARN");
        assertThat(read.logger()).isEqualTo("com.example.Db");
        assertThat(read.thread()).isEqualTo("worker-1");
    }

    @Test
    void masksTheCredentialAfterAnAuthorizationSchemeInTheSnapshotAndTheStream() {
        LogTailBuffer buffer = new LogTailBuffer();
        LogLineDto captured = line("calling api with Authorization: Bearer tok-42\nretrying with Bearer 0123abcd9");
        buffer.add(captured);
        LogTailReader reader = new LogTailReader(buffer, policy);
        String masked = "calling api with Authorization: Bearer ******\nretrying with Bearer ******";

        assertThat(reader.recent().get(0).message()).isEqualTo(masked);
        assertThat(reader.expose(captured).message()).isEqualTo(masked);
    }

    @Test
    void omitsMessagesButKeepsMetadataUnderMetadataOnly() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line(SECRET_MESSAGE));
        mode.set(ValueExposure.METADATA_ONLY);

        LogLineDto read = new LogTailReader(buffer, policy).recent().get(0);

        assertThat(read).isEqualTo(new LogLineDto(42L, "WARN", "com.example.Db", null, "worker-1", true));
    }

    @Test
    void returnsMessagesVerbatimUnderFullAndWhenMaskingIsOff() {
        LogTailBuffer buffer = new LogTailBuffer();
        LogLineDto captured = line(SECRET_MESSAGE);
        buffer.add(captured);
        LogTailReader reader = new LogTailReader(buffer, policy);

        mode.set(ValueExposure.FULL);
        assertThat(reader.recent()).containsExactly(captured);

        mode.set(ValueExposure.MASKED);
        maskSecrets.set(false);
        assertThat(reader.recent()).containsExactly(captured);
    }

    @Test
    void keepsANullMessageDistinctFromAnOmittedOne() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line(null));
        LogTailReader reader = new LogTailReader(buffer, policy);

        assertThat(reader.recent().get(0).message()).isNull();
        assertThat(reader.recent().get(0).messageOmitted()).isFalse();

        mode.set(ValueExposure.METADATA_ONLY);
        assertThat(reader.recent().get(0).messageOmitted()).isTrue();
    }

    @Test
    void appliesALiveExposureChangeToRetainedLinesOnTheNextSnapshot() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line(SECRET_MESSAGE));
        LogTailReader reader = new LogTailReader(buffer, policy);

        mode.set(ValueExposure.FULL);
        assertThat(reader.recent().get(0).message()).isEqualTo(SECRET_MESSAGE);

        mode.set(ValueExposure.METADATA_ONLY);
        assertThat(reader.recent().get(0).message()).isNull();

        mode.set(ValueExposure.MASKED);
        assertThat(reader.recent().get(0).message()).doesNotContain("hunter2");
    }

    @Test
    void streamsCapturedLinesThatExposeUnderThePolicyInForceWhenEachIsDelivered() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line("backlog password=hunter2"));
        LogTailReader reader = new LogTailReader(buffer, policy);
        List<LogLineDto> delivered = new CopyOnWriteArrayList<>();

        LogTailBuffer.Subscription subscription = reader.subscribeWithReplay(delivered::add);
        List<LogLineDto> backlog =
                subscription.backlog().stream().map(reader::expose).toList();
        buffer.add(line("live password=hunter2"));
        LogLineDto liveMasked = reader.expose(delivered.get(0));
        mode.set(ValueExposure.METADATA_ONLY);
        LogLineDto liveOmitted = reader.expose(delivered.get(0));
        subscription.unsubscribe().run();

        assertThat(backlog).extracting(LogLineDto::message).containsExactly("backlog password=******");
        assertThat(delivered)
                .as("the buffer keeps and delivers captured lines; exposure happens on read")
                .extracting(LogLineDto::message)
                .containsExactly("live password=hunter2");
        assertThat(liveMasked.message()).isEqualTo("live password=******");
        assertThat(liveOmitted.message()).isNull();
        assertThat(liveOmitted.messageOmitted()).isTrue();
    }

    @Test
    void neverMasksAtCaptureTime() {
        LogTailBuffer buffer = new LogTailBuffer();
        new LogTailReader(buffer, policy);
        buffer.add(line(SECRET_MESSAGE));

        assertThat(buffer.recent().get(0).message()).isEqualTo(SECRET_MESSAGE);
    }

    private static LogLineDto line(String message) {
        return new LogLineDto(42L, "WARN", "com.example.Db", message, "worker-1");
    }
}
