package io.github.jdubois.bootui.sample.errorcontract;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A host-application advice that happens to live under BootUI's own top-level package, exactly like
 * BootUI's sample applications (`io.github.jdubois.bootui.sample`).
 *
 * <p>It exists to pin a regression: excluding the bare {@code io.github.jdubois.bootui} prefix silently
 * hid every handler such an application declares. Self-exclusion must use the same narrow package
 * boundary as {@code BootUiSelfDataFilter} instead.</p>
 */
@RestControllerAdvice
public class HostApplicationAdvice {

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handle() {
        return ProblemDetail.forStatus(HttpStatus.CONFLICT);
    }
}
