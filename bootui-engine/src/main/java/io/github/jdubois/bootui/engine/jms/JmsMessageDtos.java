package io.github.jdubois.bootui.engine.jms;

import io.github.jdubois.bootui.core.dto.JmsMessageDto;
import io.github.jdubois.bootui.core.dto.JmsReport;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder.CapturedMessage;

/** Framework-neutral mapping from JMS capture records to the dedicated panel DTOs. */
public final class JmsMessageDtos {

    private JmsMessageDtos() {}

    public static JmsReport toReport(JmsActivityRecorder recorder) {
        var messages = recorder.recent().stream().map(JmsMessageDtos::toDto).toList();
        return new JmsReport(
                true,
                null,
                recorder.isEnabled(),
                recorder.isCaptureMessageId(),
                recorder.getMaxEntries(),
                recorder.totalCaptured(),
                messages.size(),
                messages);
    }

    public static JmsMessageDto toDto(CapturedMessage message) {
        return new JmsMessageDto(
                message.id(),
                message.timestamp(),
                message.direction().name(),
                message.destination(),
                message.messageId(),
                message.durationMillis(),
                message.success(),
                message.failureType(),
                message.subscriptionName(),
                message.listenerId());
    }
}
