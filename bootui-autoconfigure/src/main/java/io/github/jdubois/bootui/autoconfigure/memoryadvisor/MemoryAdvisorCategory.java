package io.github.jdubois.bootui.autoconfigure.memoryadvisor;

enum MemoryAdvisorCategory {
    HEAP_PRESSURE("Heap pressure"),
    MEMORY_POOLS("Memory pools"),
    GC_CONFIGURATION("GC configuration"),
    THREADS("Threads"),
    HEAP_CONTENT("Heap content"),
    CLASS_LOADING("Class loading");

    private final String label;

    MemoryAdvisorCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
