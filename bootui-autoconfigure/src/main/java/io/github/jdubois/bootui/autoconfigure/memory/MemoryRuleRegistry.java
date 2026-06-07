package io.github.jdubois.bootui.autoconfigure.memory;

import java.util.List;

final class MemoryRuleRegistry {

    private static final List<MemoryRule> ACTIVE_RULES = List.of(
            // Heap pressure
            new HighHeapUtilizationRule(),
            new OldGenerationNearMaxRule(),
            new UnsetOrSmallMaxHeapRule(),
            // Memory pools
            new MetaspaceSaturationRule(),
            new CodeCacheSaturationRule(),
            new DirectBufferGrowthRule(),
            // GC configuration
            new GcChoiceVsHeapSizeRule(),
            new MissingHeapSizingInContainerRule(),
            // Threads
            new DeadlockDetectedRule(),
            new HighBlockedThreadRatioRule(),
            new ThreadPoolExhaustionGapRule(),
            new RunawayCpuThreadRule(),
            // Heap content
            new BigObjectsRule(),
            new CollectionBloatRule(),
            new DominantClassRule(),
            // Class loading
            new ExcessiveLoadedClassesRule());

    private MemoryRuleRegistry() {}

    static List<MemoryRule> activeRules() {
        return ACTIVE_RULES;
    }
}
