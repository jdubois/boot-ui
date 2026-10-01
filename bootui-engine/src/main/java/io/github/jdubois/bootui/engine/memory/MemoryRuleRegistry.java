package io.github.jdubois.bootui.engine.memory;

import java.util.List;

final class MemoryRuleRegistry {

    /**
     * Retired IDs are never reused: MEM-HEAP-007, MEM-FOOTPRINT-004, MEM-POOL-006, MEM-THREAD-003,
     * MEM-CONTENT-004 (see docs/MEMORY-CHECKS.md).
     */
    private static final List<MemoryRule> ACTIVE_RULES = List.of(
            // Heap pressure
            new HighHeapUtilizationRule(),
            new OldGenerationNearMaxRule(),
            new SmallMaxHeapUnderPressureRule(),
            new CompressedOopsCliffRule(),
            new PendingFinalizationBacklogRule(),
            new OldGenerationTrendingUpwardRule(),
            // Native memory
            new CommittedFootprintNearContainerLimitRule(),
            new PlatformThreadStackReservationRule(),
            new ContainerMemoryPressureRule(),
            // Memory pools
            new MetaspaceSaturationRule(),
            new CodeCacheSaturationRule(),
            new DirectBufferGrowthRule(),
            new UnboundedMetaspaceInContainerRule(),
            new CompressedClassSpaceRule(),
            new BufferPoolGrowthWithoutReleaseRule(),
            // GC configuration
            new MissingHeapSizingInContainerRule(),
            new ContainerSupportDisabledRule(),
            new HighGcOverheadRule(),
            new RecentGcOverheadRule(),
            new UnequalInitialAndMaxHeapRule(),
            new SerialGcOnMultiCoreRule(),
            new G1FullGcFrequencyRule(),
            new GcEventDurationOutlierRule(),
            new NonGenerationalZgcRule(),
            // Threads
            new DeadlockDetectedRule(),
            new HighBlockedThreadRatioRule(),
            new RunawayCpuThreadRule(),
            // Heap content
            new BigObjectsRule(),
            new CollectionBloatRule(),
            new DominantClassRule(),
            // Class loading
            new ExcessiveLoadedClassesRule(),
            new ClassLoadingChurnRule());

    private MemoryRuleRegistry() {}

    static List<MemoryRule> activeRules() {
        return ACTIVE_RULES;
    }
}
