package io.github.jdubois.bootui.engine.memory;

import java.util.List;

final class MemoryRuleRegistry {

    private static final List<MemoryRule> ACTIVE_RULES = List.of(
            // Heap pressure
            new HighHeapUtilizationRule(),
            new OldGenerationNearMaxRule(),
            new UnsetOrSmallMaxHeapRule(),
            new CompressedOopsCliffRule(),
            new PendingFinalizationBacklogRule(),
            new OverProvisionedHeapRule(),
            // Native memory
            new CommittedFootprintNearContainerLimitRule(),
            new PlatformThreadStackReservationRule(),
            new ContainerMemoryPressureRule(),
            new HighSwapUtilizationRule(),
            // Memory pools
            new MetaspaceSaturationRule(),
            new CodeCacheSaturationRule(),
            new DirectBufferGrowthRule(),
            new UnboundedMetaspaceInContainerRule(),
            new CompressedClassSpaceRule(),
            new InterpretedJitModeRule(),
            // GC configuration
            new MissingHeapSizingInContainerRule(),
            new HighGcOverheadRule(),
            new RecentGcOverheadRule(),
            new UnequalInitialAndMaxHeapRule(),
            new SerialGcOnMultiCoreRule(),
            new G1FullGcFrequencyRule(),
            // Threads
            new DeadlockDetectedRule(),
            new HighBlockedThreadRatioRule(),
            new ThreadPoolExhaustionGapRule(),
            new RunawayCpuThreadRule(),
            // Heap content
            new BigObjectsRule(),
            new CollectionBloatRule(),
            new DominantClassRule(),
            new ArrayDominanceRule(),
            // Class loading
            new ExcessiveLoadedClassesRule(),
            new ClassLoadingChurnRule());

    private MemoryRuleRegistry() {}

    static List<MemoryRule> activeRules() {
        return ACTIVE_RULES;
    }
}
