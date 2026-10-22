package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.engine.advisor.AdvisorFindingIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Canonical identities of Hibernate findings, derived from the mapped structure a finding is about: the persistence
 * unit, then the entity and member, the repository and method, or the setting. Never from display text.
 *
 * <p>Version {@value #VERSION} names these key semantics; change it whenever a key changes meaning.</p>
 */
final class HibernateIdentities {

    static final String VERSION = "v1";

    private HibernateIdentities() {}

    /** An attribute of {@code entity}, keyed on the concrete entity so an inherited attribute stays distinct. */
    static String of(
            HibernateContext context, HibernateEntityModel entity, HibernateAttributeModel attribute, String... extra) {
        return key(context, parts(entityPart(entity), "member=" + attribute.name(), extra));
    }

    /** An attribute known only by the class that declares it. */
    static String of(HibernateContext context, HibernateAttributeModel attribute, String... extra) {
        return key(context, parts("entity=" + attribute.entityName(), "member=" + attribute.name(), extra));
    }

    static String of(HibernateContext context, HibernateEntityModel entity, String... extra) {
        return key(context, parts(entityPart(entity), null, extra));
    }

    /** A repository query method, keyed with its parameter types so overloads stay distinct. */
    static String of(
            HibernateContext context,
            HibernateRepositoryModel repository,
            HibernateRepositoryMethodModel method,
            String... extra) {
        return key(context, parts("repository=" + repository.repositoryInterface(), methodPart(method), extra));
    }

    static String of(HibernateContext context, HibernateRepositoryMethodModel method, String... extra) {
        return key(context, parts("repository=" + method.repositoryInterface(), methodPart(method), extra));
    }

    /** A finding about configuration, keyed on the setting it concerns. */
    static String setting(HibernateContext context, String setting) {
        return key(context, "setting=" + setting);
    }

    private static String entityPart(HibernateEntityModel entity) {
        return "entity=" + (entity.javaType() != null ? entity.javaType().getName() : entity.name());
    }

    private static String methodPart(HibernateRepositoryMethodModel method) {
        return "method=" + method.methodName() + "("
                + method.parameterTypes().stream().map(Class::getName).collect(Collectors.joining(","))
                + ")";
    }

    private static String[] parts(String first, String second, String... extra) {
        List<String> all = new ArrayList<>();
        all.add(first);
        if (second != null) all.add(second);
        all.addAll(List.of(extra));
        return all.toArray(String[]::new);
    }

    private static String key(HibernateContext context, String... parts) {
        String[] all = new String[parts.length + 1];
        all[0] = context.unitPart();
        System.arraycopy(parts, 0, all, 1, parts.length);
        return AdvisorFindingIdentity.of(VERSION, all);
    }
}
