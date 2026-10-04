package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Weak defining-loader identities, retained across sensor switches and releases. Advice keeps only the primitive token,
 * so a retained old application object cannot acquire the new run's eligibility through a retransformation.
 */
final class InventoryDefinitions {

    private final Map<LoaderKey, Definition> definitions = new HashMap<>();
    private final ReferenceQueue<ClassLoader> collected = new ReferenceQueue<>();

    synchronized int token(ClassLoader loader) {
        return definition(loader).token;
    }

    synchronized void observed(ClassLoader loader, boolean loaded) {
        Definition definition = definition(loader);
        if (!definition.observed) {
            definition.observed = true;
            definition.firstGeneration = loaded ? -1L : CodeInventory.currentGeneration();
            promote(definition, definition.firstGeneration);
        }
    }

    private Definition definition(ClassLoader loader) {
        if (loader == null) {
            return new Definition(0, 0);
        }
        LoaderKey dead;
        while ((dead = (LoaderKey) collected.poll()) != null) {
            definitions.remove(dead);
        }
        LoaderKey key = new LoaderKey(loader, collected);
        Definition known = definitions.get(key);
        if (known != null) {
            return known;
        }
        int parent = token(loader.getParent());
        Definition created = new Definition(CodeInventory.definitionToken(), parent);
        if (created.token >= 0) {
            definitions.put(key, created);
        }
        return created;
    }

    synchronized void claimed(long generation) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        while (loader != null) {
            CodeInventory.activateDefinition(token(loader), generation);
            loader = loader.getParent();
        }
        boolean promoted;
        do {
            promoted = false;
            for (Definition definition : definitions.values()) {
                if (definition.firstGeneration == generation && !CodeInventory.eligibleDefinition(definition.token)) {
                    promoted |= promote(definition, generation);
                }
            }
        } while (promoted && CodeInventory.currentGeneration() == generation);
    }

    private boolean promote(Definition definition, long generation) {
        if (generation >= 0 && CodeInventory.eligibleDefinition(definition.parent)) {
            CodeInventory.activateDefinition(definition.token, generation);
            return CodeInventory.eligibleDefinition(definition.token);
        }
        return false;
    }

    private static final class Definition {

        final int token;
        final int parent;
        boolean observed;
        long firstGeneration = -1L;

        Definition(int token, int parent) {
            this.token = token;
            this.parent = parent;
        }
    }

    private static final class LoaderKey extends WeakReference<ClassLoader> {

        private final int hash;

        LoaderKey(ClassLoader loader, ReferenceQueue<ClassLoader> collected) {
            super(loader, collected);
            hash = System.identityHashCode(loader);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof LoaderKey key && get() != null && get() == key.get());
        }
    }
}
