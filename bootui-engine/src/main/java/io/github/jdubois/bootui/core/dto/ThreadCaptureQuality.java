package io.github.jdubois.bootui.core.dto;

import java.util.List;

/** Scope and omissions of a platform-thread observation, independent of result pagination. */
public record ThreadCaptureQuality(
        String provider,
        List<String> threadKinds,
        int enumeratedThreads,
        int capturedThreads,
        int vanishedThreads,
        int omittedThreads,
        int maxStackDepth,
        boolean populationComplete,
        boolean stacksComplete,
        boolean monitorsRead,
        boolean synchronizersRead,
        List<String> limitations) {

    public ThreadCaptureQuality {
        threadKinds = DtoCollections.immutableCopy(threadKinds);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
