package io.github.jdubois.bootui.autoconfigure.memoryadvisor;

import java.util.List;

final class MemoryAdvisorRuleRegistry {

    private static final List<MemoryAdvisorRule> ACTIVE_RULES = List.of(
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

    private MemoryAdvisorRuleRegistry() {}

    static List<MemoryAdvisorRule> activeRules() {
        return ACTIVE_RULES;
    }
}
