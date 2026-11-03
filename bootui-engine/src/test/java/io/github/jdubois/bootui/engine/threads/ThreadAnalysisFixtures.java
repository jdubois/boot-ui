package io.github.jdubois.bootui.engine.threads;

import io.github.jdubois.bootui.core.dto.CapturedThreadDto;
import io.github.jdubois.bootui.core.dto.ThreadCaptureQuality;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import io.github.jdubois.bootui.engine.journal.ThreadFamilies;
import io.github.jdubois.bootui.engine.support.PagedList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ThreadAnalysisFixtures {
    private ThreadAnalysisFixtures() {}

    static CapturedThreadDto row(long id, String name, String state, Long owner, String frame) {
        var thread = new ThreadInfoDto(
                id,
                name,
                state,
                5,
                true,
                false,
                100L,
                50L,
                0,
                0,
                false,
                false,
                false,
                owner == null ? null : "example.Lock@123",
                owner,
                owner == null ? null : "owner-" + owner,
                frame == null ? List.of() : List.of(frame));
        return new CapturedThreadDto(
                "capture:" + id,
                thread,
                ThreadFamilies.of(name),
                ThreadDumpService.activity(thread),
                false,
                false,
                List.of());
    }

    static ThreadSnapshot snapshot(long millis, CapturedThreadDto... rows) {
        var quality = new ThreadCaptureQuality(
                "ThreadMXBean",
                List.of("PLATFORM"),
                rows.length,
                rows.length,
                0,
                0,
                128,
                true,
                true,
                true,
                false,
                List.of());
        var threads =
                java.util.Arrays.stream(rows).map(CapturedThreadDto::thread).toList();
        Map<String, Integer> states = new java.util.TreeMap<>();
        threads.forEach(thread -> states.merge(thread.state(), 1, Integer::sum));
        List<ThreadStateCountDto> counts = states.entrySet().stream()
                .map(entry -> new ThreadStateCountDto(entry.getKey(), entry.getValue()))
                .toList();
        var report = new ThreadDumpReport(
                true,
                null,
                millis,
                threads.size(),
                threads.size(),
                threads.size(),
                threads.size(),
                true,
                true,
                false,
                List.of(),
                counts,
                threads,
                PagedList.from(threads, 0, 1000).page(),
                quality);
        Map<Long, ThreadSnapshot.CpuReading> cpu = new LinkedHashMap<>();
        threads.forEach(thread -> cpu.put(
                thread.id(), new ThreadSnapshot.CpuReading(millis * 100_000L, millis * 50_000L, millis * 1_000_000L)));
        return new ThreadSnapshot(
                report,
                new ArrayList<>(List.of(rows)),
                cpu,
                Map.of(),
                millis + 1,
                1_000_000L,
                4096 + 2048L * threads.size(),
                threads.stream()
                        .filter(thread -> thread.name().startsWith("bootui-"))
                        .map(io.github.jdubois.bootui.core.dto.ThreadInfoDto::id)
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
