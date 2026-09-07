package io.github.jdubois.bootui.engine.hibernate;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deliberately small JPQL subset, not a query parser or a query-plan guarantee. */
final class HibernateQueryShape {
    private static final Pattern ROOT = Pattern.compile("(?i)\\bfrom\\s+([\\w.$]+)\\s+(?:as\\s+)?([A-Za-z_]\\w*)\\b");
    private static final Pattern JOIN = Pattern.compile(
            "(?i)\\bjoin\\s+(?:fetch\\s+)?([\\w.]+)(?:\\s+(?:as\\s+)?(?!left\\b|right\\b|inner\\b|outer\\b|join\\b|where\\b|order\\b|group\\b|having\\b|on\\b)([A-Za-z_]\\w*))?");

    private HibernateQueryShape() {}

    static String lexical(String query) {
        if (query == null || query.length() > 16_384) return null;
        StringBuilder result = new StringBuilder(query.length());
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                boolean closed = false;
                result.append(' ');
                while (++i < query.length()) {
                    if (query.charAt(i) == quote) {
                        if (i + 1 < query.length() && query.charAt(i + 1) == quote) {
                            i++;
                            continue;
                        }
                        closed = true;
                        break;
                    }
                }
                if (!closed) return null;
            } else if (c == '-' && i + 1 < query.length() && query.charAt(i + 1) == '-') {
                while (i + 1 < query.length() && query.charAt(i + 1) != '\n') i++;
                result.append(' ');
            } else if (c == '/' && i + 1 < query.length() && query.charAt(i + 1) == '*') {
                int end = query.indexOf("*/", i + 2);
                if (end < 0) return null;
                i = end + 1;
                result.append(' ');
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    static HibernateEntityModel entityRoot(HibernateContext context, HibernateRepositoryMethodModel method) {
        return root(context, method, true);
    }

    static HibernateEntityModel root(
            HibernateContext context, HibernateRepositoryMethodModel method, boolean entityReturn) {
        if (method.nativeQuery() || !method.hasQuery()) return null;
        HibernateQueryEvidence evidence = method.evidence();
        if (context.observed()
                && (!evidence.verifiedQueryMethod() || evidence.namedQuery() || evidence.queryRewriter())) {
            context.missingEvidence();
            return null;
        }
        String query = lexical(method.query());
        if (query == null
                || !query.trim().toLowerCase(Locale.ROOT).matches("(?s)^(select\\b|from\\b).*")
                || query.indexOf(',') >= 0) {
            context.missingEvidence();
            return null;
        }
        Matcher roots = ROOT.matcher(query);
        if (!roots.find()) {
            context.missingEvidence();
            return null;
        }
        String name = roots.group(1);
        String alias = roots.group(2);
        if (roots.find()
                || Pattern.compile("(?i)\\b(select|union|intersect|except)\\b")
                        .matcher(query.substring(query.toLowerCase(Locale.ROOT).indexOf("from") + 4))
                        .find()) {
            context.missingEvidence();
            return null;
        }
        Matcher joins = JOIN.matcher(query);
        while (joins.find()) {
            String path = joins.group(1);
            if (!path.startsWith(alias + ".")
                    || path.chars().filter(value -> value == '.').count() != 1
                    || joins.group(2) != null) {
                context.missingEvidence();
                return null;
            }
        }
        HibernateEntityModel found = null;
        for (HibernateEntityModel entity : context.entities()) {
            if (entity.javaType() == null) continue;
            String entityName = entity.annotationStringValue(entity.annotation("jakarta.persistence.Entity"), "name");
            if (entityName == null || entityName.isBlank())
                entityName = entity.javaType().getSimpleName();
            if (name.equals(entityName) || name.equals(entity.javaType().getName())) {
                if (found != null) {
                    context.missingEvidence();
                    return null;
                }
                found = entity;
            }
        }
        if (found == null) {
            context.missingEvidence();
            return null;
        }
        if (entityReturn && context.observed() && evidence.returnElementType() == null) {
            context.missingEvidence();
            return null;
        }
        if (entityReturn && context.observed() && !found.javaType().equals(evidence.returnElementType())) return null;
        return found;
    }

    static boolean bulk(String query) {
        String text = lexical(query);
        return text != null && text.stripLeading().toLowerCase(Locale.ROOT).matches("(?s)^(update\\b|delete\\b).*");
    }
}
