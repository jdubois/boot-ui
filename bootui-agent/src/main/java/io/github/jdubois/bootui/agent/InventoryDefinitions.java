package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Weak defining-loader identities, retained across sensor switches and releases. Advice keeps only the primitive token,
 * so a retained old application object cannot acquire the new run's eligibility through a retransformation.
 */
final class InventoryDefinitions {

    private final Map<LoaderKey, Definition> definitions = new HashMap<>();
    private final ReferenceQueue<ClassLoader> collected = new ReferenceQueue<>();
    private final ReferenceQueue<ClassLoader> unloaded = new ReferenceQueue<>();
    private final Set<PhantomToken> leased = new HashSet<>();

    synchronized int token(ClassLoader loader) {
        Definition definition = definition(loader);
        if (definition.token == -2) {
            definition.parent = token(loader.getParent());
            if (!definition.observed) {
                definition.observed = true;
                definition.firstGeneration = CodeInventory.currentGeneration();
            }
            definition.token = CodeInventory.definitionToken();
            if (definition.token > 0) {
                leased.add(new PhantomToken(loader, unloaded, definition.token));
                promote(definition, definition.firstGeneration);
            }
        } else if (definition.token == -1) {
            CodeInventory.definitionLimitReached();
        }
        return definition.token;
    }

    synchronized void observed(ClassLoader loader, boolean existingAtClaim) {
        Definition definition = definition(loader);
        if (!definition.observed) {
            definition.observed = true;
            definition.firstGeneration = existingAtClaim ? -1L : CodeInventory.currentGeneration();
            promote(definition, definition.firstGeneration);
        }
    }

    private Definition definition(ClassLoader loader) {
        if (loader == null) {
            Definition bootstrap = new Definition();
            bootstrap.token = 0;
            return bootstrap;
        }
        LoaderKey dead;
        while ((dead = (LoaderKey) collected.poll()) != null) {
            definitions.remove(dead);
        }
        PhantomToken retired;
        while ((retired = (PhantomToken) unloaded.poll()) != null) {
            leased.remove(retired);
            CodeInventory.releaseDefinitionToken(retired.token);
        }
        LoaderKey key = new LoaderKey(loader, collected);
        Definition known = definitions.get(key);
        if (known != null) {
            return known;
        }
        Definition created = new Definition();
        definitions.put(key, created);
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
            for (Map.Entry<LoaderKey, Definition> entry : definitions.entrySet()) {
                Definition definition = entry.getValue();
                if (entry.getKey().get() != null
                        && definition.token > 0
                        && definition.firstGeneration == generation
                        && !CodeInventory.eligibleDefinition(definition.token)) {
                    promoted |= promote(definition, generation);
                }
            }
        } while (promoted && CodeInventory.currentGeneration() == generation);
    }

    private boolean promote(Definition definition, long generation) {
        if (definition.token > 0 && generation >= 0 && CodeInventory.eligibleDefinition(definition.parent)) {
            CodeInventory.activateDefinition(definition.token, generation);
            return CodeInventory.eligibleDefinition(definition.token);
        }
        return false;
    }

    private static final class Definition {

        int token = -2;
        int parent;
        boolean observed;
        long firstGeneration = -1L;
    }

    private static final class PhantomToken extends PhantomReference<ClassLoader> {

        final int token;

        PhantomToken(ClassLoader loader, ReferenceQueue<ClassLoader> unloaded, int token) {
            super(loader, unloaded);
            this.token = token;
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
