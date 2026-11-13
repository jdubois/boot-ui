package io.github.jdubois.bootui.autoconfigure.compatibility;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

public final class BootUiCompatibilityFailureAnalyzer extends AbstractFailureAnalyzer<BootUiCompatibilityException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, BootUiCompatibilityException cause) {
        return new FailureAnalysis(cause.getMessage(), cause.action(), cause);
    }
}
