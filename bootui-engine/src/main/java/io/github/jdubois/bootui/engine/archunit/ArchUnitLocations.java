package io.github.jdubois.bootui.engine.archunit;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.domain.ThrowsDeclaration;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.core.domain.properties.HasSourceCodeLocation;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Maps ArchUnit's own domain objects to a neutral {@link AdvisorViolationLocationDto}, never by parsing report
 * text. ArchUnit and its types stay inside the engine; only the neutral record leaves this class.
 *
 * <p>A class alone is located at {@code CLASS} precision, a member at {@code MEMBER} precision, and anything
 * ArchUnit records a positive line for, such as a method body or a call inside it, at {@code LINE} precision.
 * A compiler-generated member (a lambda body, a bridge, a Kotlin {@code $default}) keeps its class and line
 * but not its name, because that name does not exist in the source.</p>
 */
public final class ArchUnitLocations {

    private static final int MAX_DEPTH = 4;

    private ArchUnitLocations() {}

    /**
     * The one code element {@code objects} point at, or {@code null} when they are empty, point at several
     * elements, or at something that is not a code element (such as a package slice or a cycle).
     */
    public static AdvisorViolationLocationDto of(Collection<?> objects) {
        if (objects == null || objects.isEmpty()) return null;
        Set<AdvisorViolationLocationDto> locations = new LinkedHashSet<>();
        for (Object object : objects) {
            AdvisorViolationLocationDto location = of(object);
            if (location == null) return null;
            locations.add(location);
            if (locations.size() > 1) return null;
        }
        return locations.iterator().next();
    }

    /** The one code element {@code object} points at, or {@code null}. */
    public static AdvisorViolationLocationDto of(Object object) {
        try {
            return of(object, 0);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    public static AdvisorViolationLocationDto ofClass(JavaClass type) {
        return of(type);
    }

    public static AdvisorViolationLocationDto ofMember(JavaMember member) {
        return of(member);
    }

    private static AdvisorViolationLocationDto of(Object object, int depth) {
        if (object == null || depth > MAX_DEPTH) return null;
        if (object instanceof JavaClass type) {
            return location(type, null, null);
        }
        if (object instanceof JavaMember member) {
            Integer line = member instanceof JavaCodeUnit
                    ? member.getSourceCodeLocation().getLineNumber()
                    : null;
            return location(member.getOwner(), member, line);
        }
        if (object instanceof JavaAccess<?> access) {
            return location(access.getOriginOwner(), access.getOrigin(), access.getLineNumber());
        }
        if (object instanceof Dependency dependency) {
            Set<JavaAccess> accesses = dependency.convertTo(JavaAccess.class);
            if (accesses.size() == 1) return of(accesses.iterator().next(), depth + 1);
            return location(
                    dependency.getOriginClass(),
                    null,
                    dependency.getSourceCodeLocation().getLineNumber());
        }
        if (object instanceof JavaParameter parameter) {
            return of(parameter.getOwner(), depth + 1);
        }
        if (object instanceof JavaAnnotation<?> annotation) {
            return of(annotation.getOwner(), depth + 1);
        }
        if (object instanceof ThrowsDeclaration<?> declaration) {
            return of(declaration.getLocation(), depth + 1);
        }
        if (object instanceof HasOwner<?> owned
                && owned.getOwner() instanceof JavaCodeUnit codeUnit
                && object instanceof HasSourceCodeLocation located) {
            // InstanceofCheck, ReferencedClassObject, TryCatchBlock: a line inside one code unit.
            return location(
                    codeUnit.getOwner(),
                    codeUnit,
                    located.getSourceCodeLocation().getLineNumber());
        }
        return null;
    }

    private static AdvisorViolationLocationDto location(JavaClass type, JavaMember member, Integer line) {
        if (type == null) return null;
        String memberName = null;
        String kind = AdvisorViolationLocationDto.CLASS;
        if (member != null && !KotlinBytecode.isCompilerGenerated(member)) {
            if (member instanceof JavaMethod) {
                memberName = member.getName();
                kind = AdvisorViolationLocationDto.METHOD;
            } else if (member instanceof JavaConstructor) {
                memberName = member.getName();
                kind = AdvisorViolationLocationDto.CONSTRUCTOR;
            } else if (member instanceof JavaField) {
                memberName = member.getName();
                kind = AdvisorViolationLocationDto.FIELD;
            }
        }
        String sourceFile = type.getSource().flatMap(Source::getFileName).orElse(null);
        return new AdvisorViolationLocationDto(type.getName(), memberName, kind, sourceFile, line, null);
    }

    /** The class file a location's class was imported from, or empty when ArchUnit recorded none. */
    public static Optional<java.net.URI> classFile(JavaClass type) {
        return type.getSource().map(Source::getUri);
    }
}
