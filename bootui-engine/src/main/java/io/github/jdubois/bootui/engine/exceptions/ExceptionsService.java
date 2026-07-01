package io.github.jdubois.bootui.engine.exceptions;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ExceptionCauseDto;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionFrameDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionOccurrenceDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.regex.Pattern;

/**
 * Framework-neutral assembly + display masking for the Exceptions panel, shared by the Spring Boot and
 * Quarkus adapters. Adapters own only capture (feeding the {@link ExceptionStore}) and transport; every
 * read transformation lives here so the wire ({@link ExceptionsReport} / {@link ExceptionDetailDto}) is
 * identical across platforms and the single Vue panel renders the same.
 *
 * <p>Messages are surfaced according to the configured value-exposure policy: omitted for
 * {@code METADATA_ONLY}, scrubbed of obvious secret-like assignments for the default {@code MASKED}
 * mode, and shown verbatim only for {@code FULL}. Stack frames carry only class/method/file/line. Pure
 * functions over core DTOs, an SPI {@link ExposurePolicy}, and the JDK.</p>
 */
public final class ExceptionsService {

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?(?:password|passwd|pwd|secret|token|api[-_]?key|apikey|authorization|credential|"
                    + "access[-_]?key|client[-_]?secret|private[-_]?key)[\"']?\\s*[=:]\\s*[\"']?)([^\\s\"',;&)]+)");

    private final ExposurePolicy exposure;

    public ExceptionsService(ExposurePolicy exposure) {
        this.exposure = exposure;
    }

    public ExceptionsReport report(ExceptionStore store) {
        return new ExceptionsReport(
                true,
                null,
                store.maxGroups(),
                store.totalExceptions(),
                store.groups().stream().map(this::toGroupDto).toList());
    }

    public ExceptionDetailDto detail(ExceptionStore.GroupDetail detail) {
        return new ExceptionDetailDto(
                toGroupDto(detail.summary()),
                detail.frames().stream().map(this::toFrameDto).toList(),
                detail.causes().stream().map(this::toCauseDto).toList(),
                detail.occurrences().stream().map(this::toOccurrenceDto).toList());
    }

    private ExceptionGroupDto toGroupDto(ExceptionStore.GroupSummary summary) {
        ExceptionStore.Occurrence last = summary.last();
        return new ExceptionGroupDto(
                summary.fingerprint(),
                summary.exceptionClassName(),
                displayMessage(summary.message()),
                summary.count(),
                summary.firstSeen(),
                summary.lastSeen(),
                summary.location(),
                summary.applicationException(),
                last == null ? null : last.thread(),
                last == null ? null : last.requestMethod(),
                last == null ? null : last.requestPath(),
                last == null ? null : last.handler(),
                last == null ? null : last.source(),
                last == null ? null : last.traceId());
    }

    private ExceptionFrameDto toFrameDto(ExceptionStore.Frame frame) {
        return new ExceptionFrameDto(
                frame.declaringClass(),
                frame.methodName(),
                frame.fileName(),
                frame.lineNumber() >= 0 ? frame.lineNumber() : null,
                frame.applicationFrame());
    }

    private ExceptionCauseDto toCauseDto(ExceptionStore.Cause cause) {
        return new ExceptionCauseDto(
                cause.exceptionClassName(),
                displayMessage(cause.message()),
                cause.frames().stream().map(this::toFrameDto).toList(),
                cause.commonFrames());
    }

    private ExceptionOccurrenceDto toOccurrenceDto(ExceptionStore.Occurrence occurrence) {
        return new ExceptionOccurrenceDto(
                occurrence.timestamp(),
                occurrence.thread(),
                occurrence.requestMethod(),
                occurrence.requestPath(),
                occurrence.handler(),
                occurrence.source());
    }

    private String displayMessage(String message) {
        ValueExposure valueExposure = exposure.valueExposure();
        if (valueExposure == ValueExposure.METADATA_ONLY || message == null) {
            return null;
        }
        if (valueExposure == ValueExposure.MASKED && exposure.maskSecrets()) {
            return SECRET_ASSIGNMENT.matcher(message).replaceAll(result -> result.group(1) + "******");
        }
        return message;
    }
}
