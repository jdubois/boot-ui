package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.LowFootprintStatus;
import io.github.jdubois.bootui.core.dto.SuspendedComponentDto;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.LowFootprintSuspendable;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LowFootprintServiceTests {

    private final AtomicLong heap = new AtomicLong(1_000L);
    private final AtomicInteger gcCalls = new AtomicInteger();
    private final AtomicBoolean agent = new AtomicBoolean();
    private final List<String> events = new ArrayList<>();

    private LowFootprintService service(List<Object> candidates) {
        MemoryOffloadService offload = new MemoryOffloadService(
                () -> candidates,
                heap::get,
                () -> {
                    events.add("gc");
                    gcCalls.incrementAndGet();
                    heap.set(400L);
                },
                () -> false,
                () -> 0L);
        return new LowFootprintService(() -> candidates, offload, agent::get, () -> 42L);
    }

    @Test
    void enablePausesEveryComponentThenOffloadsThenRequestsAGcAndDisableResumesThem() {
        Part journal = new Part("runtime-journal", "Runtime journal recording");
        Part exceptions = new Part("exceptions", "Exception capture");
        Idle idle = new Idle();
        List<Object> candidates = new ArrayList<>(List.of(journal, "ignored", exceptions, idle, exceptions));
        LowFootprintService service = service(candidates);

        assertThat(service.active()).isFalse();
        LowFootprintStatus on = service.enable();

        assertThat(service.active()).isTrue();
        assertThat(on.active()).isTrue();
        assertThat(on.sinceEpochMillis()).isEqualTo(42L);
        assertThat(on.components())
                .extracting(SuspendedComponentDto::id)
                .containsExactly("exceptions", "idle-store", "runtime-journal");
        assertThat(on.components()).allMatch(SuspendedComponentDto::suspended);
        assertThat(on.offload()).isNotNull();
        assertThat(on.offload().reclaimedBytes()).isEqualTo(600L);
        assertThat(journal.suspends).isEqualTo(1);
        assertThat(exceptions.suspends).isEqualTo(1);
        assertThat(idle.suspendedForIdle).isTrue();
        assertThat(gcCalls).hasValue(1);

        LowFootprintStatus off = service.disable();

        assertThat(service.active()).isFalse();
        assertThat(off.active()).isFalse();
        assertThat(off.sinceEpochMillis()).isNull();
        assertThat(off.components()).isEmpty();
        assertThat(journal.resumes).isEqualTo(1);
        assertThat(idle.suspendedForIdle).isFalse();
    }

    @Test
    void componentsArePausedBeforeTheOffloadSoNothingRefillsWhatItFrees() {
        Part journal = new Part("runtime-journal", "Runtime journal recording") {
            @Override
            public void suspendForLowFootprint() {
                events.add("suspend");
                super.suspendForLowFootprint();
            }
        };

        service(List.of(journal)).enable();

        assertThat(events).containsExactly("suspend", "gc");
    }

    @Test
    void theResumeHoldIsRaisedBeforeTheFirstComponentIsPaused() {
        AtomicBoolean heldWhilePausing = new AtomicBoolean();
        LowFootprintService[] holder = new LowFootprintService[1];
        Part part = new Part("a", "A") {
            @Override
            public void suspendForLowFootprint() {
                heldWhilePausing.set(holder[0].active());
            }
        };
        holder[0] = service(List.of(part));

        holder[0].enable();

        assertThat(heldWhilePausing).isTrue();
    }

    @Test
    void aFailingComponentIsReportedAndTheOthersStillPause() {
        Part broken = new Part("broken", "Broken") {
            @Override
            public void suspendForLowFootprint() {
                throw new IllegalStateException("cannot stop");
            }
        };
        Part healthy = new Part("healthy", "Healthy");

        LowFootprintStatus on = service(List.of(broken, healthy)).enable();

        assertThat(on.active()).isTrue();
        assertThat(on.components())
                .containsExactly(
                        new SuspendedComponentDto("broken", "Broken", false, "IllegalStateException: cannot stop"),
                        new SuspendedComponentDto("healthy", "Healthy", true, null));
        assertThat(on.warnings()).anyMatch(w -> w.contains("Broken") && w.contains("cannot stop"));
        assertThat(healthy.suspends).isEqualTo(1);
    }

    @Test
    void aFailingResumeIsReportedAndTheModeStillTurnsOff() {
        Part broken = new Part("broken", "Broken") {
            @Override
            public void resumeFromLowFootprint() {
                throw new IllegalStateException("stuck");
            }
        };
        Part healthy = new Part("healthy", "Healthy");
        LowFootprintService service = service(List.of(broken, healthy));
        service.enable();

        LowFootprintStatus off = service.disable();

        assertThat(off.active()).isFalse();
        assertThat(service.active()).isFalse();
        assertThat(healthy.resumes).isEqualTo(1);
        assertThat(off.warnings()).anyMatch(w -> w.contains("Broken") && w.contains("stuck"));
    }

    @Test
    void enablingTwiceAndDisablingTwiceChangeNothingTheSecondTime() {
        Part part = new Part("a", "A");
        LowFootprintService service = service(List.of(part));

        service.enable();
        LowFootprintStatus again = service.enable();
        service.disable();
        service.disable();

        assertThat(again.active()).isTrue();
        assertThat(again.offload()).as("no second offload").isNull();
        assertThat(part.suspends).isEqualTo(1);
        assertThat(part.resumes).isEqualTo(1);
        assertThat(gcCalls).hasValue(1);
    }

    @Test
    void anAttachedAgentIsReportedAsAWarningButNeverPaused() {
        agent.set(true);
        LowFootprintService service = service(List.of(new Part("a", "A")));

        assertThat(service.status().agentAttached()).isTrue();
        assertThat(service.status().warnings()).isEmpty();
        LowFootprintStatus on = service.enable();

        assertThat(on.agentAttached()).isTrue();
        assertThat(on.warnings()).contains(LowFootprintService.AGENT_WARNING);
    }

    @Test
    void statusDoesNoWorkAndTheMultiPartComponentIsReportedPerPart() {
        Part first = new Part("one", "One");
        Part second = new Part("two", "Two");
        Part group = new Part("group", "Group") {
            @Override
            public List<LowFootprintSuspendable> suspendableParts() {
                return List.of(this, first, second);
            }
        };
        LowFootprintService service = service(List.of(group));

        LowFootprintStatus off = service.status();
        assertThat(off.active()).isFalse();
        assertThat(off.components()).isEmpty();
        assertThat(group.suspends).isZero();

        LowFootprintStatus on = service.enable();
        assertThat(on.components()).extracting(SuspendedComponentDto::id).containsExactly("group", "one", "two");
        assertThat(service.status().components()).hasSize(3);
        assertThat(service.status().offload())
                .as("the report is only on the POST that enabled")
                .isNull();
    }

    @Test
    void neverCreatesBeansItOnlyReadsTheCandidatesTheAdapterHolds() {
        AtomicInteger reads = new AtomicInteger();
        MemoryOffloadService offload = new MemoryOffloadService(List::of);
        LowFootprintService service = new LowFootprintService(
                () -> {
                    reads.incrementAndGet();
                    return List.of();
                },
                offload,
                () -> false,
                () -> 0L);

        service.status();
        assertThat(reads).hasValue(0);
        assertThat(service.enable().components()).isEmpty();
        assertThat(reads).hasValue(1);
    }

    @Test
    void concurrentToggleStormsAreSerializedAndEndConsistent() throws Exception {
        Part part = new Part("a", "A");
        LowFootprintService service = service(List.of(part));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int worker = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < 50; round++) {
                        if ((round + worker) % 2 == 0) {
                            service.enable();
                        } else {
                            service.disable();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(part.suspends - part.resumes).isEqualTo(service.active() ? 1 : 0);
        service.disable();
        assertThat(part.suspends).isEqualTo(part.resumes);
    }

    static class Part implements LowFootprintSuspendable {
        private final String id;
        private final String label;
        int suspends;
        int resumes;

        Part(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String suspendId() {
            return id;
        }

        @Override
        public String suspendLabel() {
            return label;
        }

        @Override
        public void suspendForLowFootprint() {
            suspends++;
        }

        @Override
        public void resumeFromLowFootprint() {
            resumes++;
        }
    }

    static final class Idle implements IdleReclaimable, MemoryOffloadable {
        boolean suspendedForIdle;

        @Override
        public void suspendForIdle() {
            suspendedForIdle = true;
        }

        @Override
        public void resumeFromIdle() {
            suspendedForIdle = false;
        }

        @Override
        public String offloadId() {
            return "idle-store";
        }

        @Override
        public String offloadLabel() {
            return "Idle store";
        }

        @Override
        public long offloadRetainedData() {
            return 0;
        }
    }
}
