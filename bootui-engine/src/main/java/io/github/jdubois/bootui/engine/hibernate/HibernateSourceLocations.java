package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorLocations;
import io.github.jdubois.bootui.engine.archunit.ClassFileFacts;
import io.github.jdubois.bootui.engine.source.ClassFileLocator;
import io.github.jdubois.bootui.engine.source.SourceLocator;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Completes Hibernate locations during an explicit scan: reads each located class's recorded source file name from
 * its class file and resolves its local source path through the shared {@link SourceLocator}. Only classes the
 * scan already holds are considered (mapped entities, their superclasses, and repository interfaces), and their class
 * files are found through {@link ClassFileLocator}; no class is loaded or initialized here.
 */
final class HibernateSourceLocations {

    private static final int MAX_SUPERCLASS_DEPTH = 32;

    private HibernateSourceLocations() {}

    static AdvisorLocations.Resolution resolve(
            HibernateAdvisorObservation observation, Collection<AdvisorViolationLocationDto> located) {
        Map<String, Class<?>> types = new HashMap<>();
        Map<String, ClassLoader> loaders = new HashMap<>();
        for (HibernatePersistenceUnitObservation unit : observation.units()) {
            for (HibernateEntityModel entity : unit.entities()) {
                Class<?> type = entity.javaType();
                for (int depth = 0; type != null && type != Object.class && depth < MAX_SUPERCLASS_DEPTH; depth++) {
                    types.putIfAbsent(type.getName(), type);
                    type = type.getSuperclass();
                }
            }
            for (HibernateRepositoryModel repository : unit.repositories()) {
                Class<?> domain = repository.domainType();
                if (repository.repositoryInterface() != null && domain != null && domain.getClassLoader() != null) {
                    loaders.putIfAbsent(repository.repositoryInterface(), domain.getClassLoader());
                }
            }
        }
        Set<String> names = new LinkedHashSet<>();
        located.forEach(location -> names.add(location.className()));
        ClassFileLocator classFiles = ClassFileLocator.forScan();
        Map<String, String> sourceFiles = new LinkedHashMap<>();
        List<SourceLocator.Request> requests = new ArrayList<>();
        for (String name : names) {
            Class<?> type = types.get(name);
            ClassLoader loader = type != null ? type.getClassLoader() : loaders.get(name);
            if (loader == null) continue;
            URI classFile = classFiles.locate(name, loader, type);
            String sourceFile = sourceFile(classFile, loader, name);
            if (sourceFile != null) sourceFiles.put(name, sourceFile);
            requests.add(new SourceLocator.Request(name, classFile, sourceFile));
        }
        SourceLocator.Result result = SourceLocator.resolve(requests);
        return new AdvisorLocations.Resolution(
                location -> {
                    SourceLocator.Resolved resolved =
                            result.get(location.className()).orElse(null);
                    return new AdvisorViolationLocationDto(
                            location.className(),
                            location.memberName(),
                            location.kind(),
                            sourceFiles.getOrDefault(location.className(), location.sourceFile()),
                            null,
                            resolved == null ? null : resolved.path().toString());
                },
                result.notes());
    }

    /**
     * The source file name the class file records: read from its located file or archive, or, when its loader serves
     * it only from memory (as Quarkus does for enhanced entities), from the loader's own bytes.
     */
    private static String sourceFile(URI classFile, ClassLoader loader, String name) {
        if (classFile != null && ("file".equals(classFile.getScheme()) || "jar".equals(classFile.getScheme()))) {
            return ClassFileFacts.read(classFile)
                    .map(ClassFileFacts::sourceFile)
                    .orElse(null);
        }
        try (InputStream input = loader.getResourceAsStream(name.replace('.', '/') + ".class")) {
            return ClassFileFacts.read(input).map(ClassFileFacts::sourceFile).orElse(null);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }
}
