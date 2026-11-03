package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ThreadLockReport(
        String captureId,
        String id,
        String identity,
        String kind,
        CapturedThreadDto owner,
        List<ThreadWaitDto> waiters,
        PageMetadata page,
        List<String> limitations) {
    public ThreadLockReport {
        waiters = DtoCollections.immutableCopy(waiters);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
