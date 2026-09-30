package io.github.jdubois.bootui.engine.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorLocations;
import io.github.jdubois.bootui.engine.source.SourceLocator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;

/**
 * Completes the locations of one explicit ArchUnit-based scan: resolves each located class's local source path
 * through the shared {@link SourceLocator}, and drops any line that cannot be shown as a line of that class's
 * own source file. Runs once per scan, after every rule, and never during a detail read.
 *
 * <p>A Kotlin line is kept only when the class's SMAP maps it back to the declaring file, because inlined code
 * carries line numbers of another file. Any line beyond the end of the resolved source file is dropped too, so
 * a stale or mismatched class never points past the file it names. A dropped line leaves the location at
 * member (or class) precision.</p>
 */
public final class ArchUnitSourceLocations {

    private sealed interface LineCheck {}

    private record AllLines() implements LineCheck {}

    private record Mapped(KotlinSourceMap map) implements LineCheck {}

    private record Unverifiable() implements LineCheck {}

    private ArchUnitSourceLocations() {}

    public static AdvisorLocations.Resolution resolve(
            JavaClasses classes, Collection<AdvisorViolationLocationDto> locations) {
        return resolve(classes, locations, SourceLocator.DEFAULT_LIMITS);
    }

    /** As {@link #resolve(JavaClasses, Collection)}, with explicit lookup budgets. */
    public static AdvisorLocations.Resolution resolve(
            JavaClasses classes, Collection<AdvisorViolationLocationDto> locations, SourceLocator.Limits limits) {
        Map<String, JavaClass> located = new LinkedHashMap<>();
        Map<String, Boolean> hasLine = new HashMap<>();
        for (AdvisorViolationLocationDto location : locations) {
            if (location == null) continue;
            String name = location.className();
            hasLine.merge(name, location.line() != null, Boolean::logicalOr);
            if (!located.containsKey(name) && classes.contain(name)) located.put(name, classes.get(name));
        }
        List<SourceLocator.Request> requests = new ArrayList<>();
        Map<String, LineCheck> checks = new HashMap<>();
        located.forEach((name, type) -> {
            java.net.URI classFile = ArchUnitLocations.classFile(type).orElse(null);
            String sourceFile = type.getSource()
                    .flatMap(com.tngtech.archunit.core.domain.Source::getFileName)
                    .orElse(null);
            requests.add(new SourceLocator.Request(name, classFile, sourceFile));
            if (Boolean.TRUE.equals(hasLine.get(name)) && KotlinBytecode.isKotlinClass(type)) {
                checks.put(name, kotlinCheck(classFile));
            }
        });
        SourceLocator.Result result = SourceLocator.resolve(requests, limits);
        UnaryOperator<AdvisorViolationLocationDto> mapper = location -> {
            if (location == null) return null;
            String name = location.className();
            Integer line = location.line();
            LineCheck check = checks.getOrDefault(name, new AllLines());
            if (line != null && check instanceof Mapped mapped) {
                OptionalInt declaring = mapped.map().declaringLine(line);
                line = declaring.isPresent() ? Integer.valueOf(declaring.getAsInt()) : null;
            } else if (line != null && check instanceof Unverifiable) {
                line = null;
            }
            SourceLocator.Resolved resolved = result.get(name).orElse(null);
            if (line != null && resolved != null && line > resolved.lineCount()) line = null;
            return new AdvisorViolationLocationDto(
                    name,
                    location.memberName(),
                    location.kind(),
                    location.sourceFile(),
                    line,
                    resolved == null ? null : resolved.path().toString());
        };
        return new AdvisorLocations.Resolution(mapper, result.notes());
    }

    private static LineCheck kotlinCheck(java.net.URI classFile) {
        return ClassFileFacts.read(classFile)
                .<LineCheck>map(facts -> {
                    if (facts.sourceMap() == null) return new AllLines();
                    KotlinSourceMap map = KotlinSourceMap.parse(facts.sourceMap(), facts.sourceFile());
                    return map == null ? new Unverifiable() : new Mapped(map);
                })
                .orElseGet(Unverifiable::new);
    }
}
