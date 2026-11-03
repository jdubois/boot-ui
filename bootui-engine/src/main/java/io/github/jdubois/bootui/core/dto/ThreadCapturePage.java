package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ThreadCapturePage(
        String captureId, List<CapturedThreadDto> threads, PageMetadata page, int hiddenBootUiThreads) {
    public ThreadCapturePage {
        threads = DtoCollections.immutableCopy(threads);
    }

    public ThreadCapturePage(String captureId, List<CapturedThreadDto> threads, PageMetadata page) {
        this(captureId, threads, page, 0);
    }
}
