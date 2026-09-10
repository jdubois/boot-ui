package io.github.jdubois.bootui.engine.hibernate;

import java.util.ArrayList;
import java.util.List;
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

    private static final Pattern UPDATE_PREFIX = Pattern.compile("(?is)^\\s*update\\b");
    private static final Pattern UPDATE_HEAD = Pattern.compile("(?is)^\\s*update\\s+(.+?)\\s+set\\b");
    private static final Pattern UPDATE_VERSIONED = Pattern.compile("(?is)^\\s*update\\s+versioned\\b");
    private static final Pattern UPDATE_SET_CLAUSE = Pattern.compile("(?is)\\bset\\s+(.*?)(?:\\bwhere\\b|$)");

    static boolean isUpdate(String query) {
        String text = lexical(query);
        return text != null && UPDATE_PREFIX.matcher(text).find();
    }

    static boolean isUpdateVersioned(String query) {
        String text = lexical(query);
        return text != null && UPDATE_VERSIONED.matcher(text).find();
    }

    record UpdateTarget(HibernateEntityModel entity, String alias, String query, boolean versioned) {}

    static UpdateTarget resolveUpdateTarget(HibernateContext context, HibernateRepositoryMethodModel method) {
        if (method.nativeQuery() || !method.hasQuery()) return null;
        HibernateQueryEvidence evidence = method.evidence();
        if (context.observed()
                && (!evidence.verifiedQueryMethod() || evidence.namedQuery() || evidence.queryRewriter())) {
            context.missingEvidence();
            return null;
        }
        String query = lexical(method.query());
        if (query == null || !isUpdate(query)) {
            return null;
        }
        // Disallow subqueries or set operations (select, union, intersect, except)
        if (Pattern.compile("(?i)\\b(select|union|intersect|except)\\b")
                .matcher(query)
                .find()) {
            context.missingEvidence();
            return null;
        }
        Matcher headMatcher = UPDATE_HEAD.matcher(query);
        if (!headMatcher.find()) {
            context.missingEvidence();
            return null;
        }
        String head = headMatcher.group(1).trim();
        if (head.indexOf(',') >= 0) {
            context.missingEvidence();
            return null;
        }
        String[] tokens = head.split("\\s+");
        if (tokens.length == 0) {
            context.missingEvidence();
            return null;
        }

        boolean versioned = false;
        HibernateEntityModel entity = null;
        String alias = null;

        if (tokens[0].equalsIgnoreCase("versioned")) {
            if (tokens.length >= 2) {
                HibernateEntityModel candidate = findEntity(context, tokens[1]);
                if (candidate != null) {
                    versioned = true;
                    entity = candidate;
                    if (tokens.length == 3) {
                        alias = tokens[2];
                    } else if (tokens.length == 4 && tokens[2].equalsIgnoreCase("as")) {
                        alias = tokens[3];
                    } else if (tokens.length > 2) {
                        context.missingEvidence();
                        return null;
                    }
                }
            }
            if (entity == null) {
                entity = findEntity(context, tokens[0]);
                if (entity != null) {
                    versioned = false;
                    if (tokens.length == 2) {
                        alias = tokens[1];
                    } else if (tokens.length == 3 && tokens[1].equalsIgnoreCase("as")) {
                        alias = tokens[2];
                    } else if (tokens.length > 1) {
                        context.missingEvidence();
                        return null;
                    }
                }
            }
        } else {
            entity = findEntity(context, tokens[0]);
            if (entity != null) {
                if (tokens.length == 2) {
                    alias = tokens[1];
                } else if (tokens.length == 3 && tokens[1].equalsIgnoreCase("as")) {
                    alias = tokens[2];
                } else if (tokens.length > 1) {
                    context.missingEvidence();
                    return null;
                }
            }
        }

        if (entity == null) {
            context.missingEvidence();
            return null;
        }
        return new UpdateTarget(entity, alias, query, versioned);
    }

    private static HibernateEntityModel findEntity(HibernateContext context, String name) {
        HibernateEntityModel found = null;
        for (HibernateEntityModel entity : context.entities()) {
            if (entity.javaType() == null) continue;
            String entityName = entity.annotationStringValue(entity.annotation("jakarta.persistence.Entity"), "name");
            if (entityName == null || entityName.isBlank()) {
                entityName = entity.javaType().getSimpleName();
            }
            if (name.equals(entityName) || name.equals(entity.javaType().getName())) {
                if (found != null) {
                    return null;
                }
                found = entity;
            }
        }
        return found;
    }

    static boolean maintainsVersion(String query, String alias, String versionAttributeName) {
        if (query == null || versionAttributeName == null || versionAttributeName.isBlank()) {
            return false;
        }
        Matcher setMatcher = UPDATE_SET_CLAUSE.matcher(query);
        if (!setMatcher.find()) {
            return false;
        }
        String setBody = setMatcher.group(1);
        List<String> assignments = splitTopLevel(setBody, ',');
        String quotedVersion = Pattern.quote(versionAttributeName);
        String targetPatternStr;
        if (alias != null && !alias.isBlank()) {
            String quotedAlias = Pattern.quote(alias);
            targetPatternStr = "(?:(?:" + quotedAlias + "\\s*\\.\\s*)?" + quotedVersion + ")";
        } else {
            targetPatternStr = quotedVersion;
        }
        Pattern assignPattern = Pattern.compile("(?is)^\\s*" + targetPatternStr + "\\s*=(.*)$");
        Pattern literalNumber = Pattern.compile("^\\d+[lL]?$");
        for (String assignment : assignments) {
            Matcher m = assignPattern.matcher(assignment.trim());
            if (m.matches()) {
                String rhs = unwrapBalancedParens(m.group(1).trim());
                Pattern rhsSelf = Pattern.compile("(?is)^" + targetPatternStr + "$");
                if (rhsSelf.matcher(rhs).matches()) {
                    continue;
                }
                List<String> parts = splitTopLevel(rhs, '+');
                if (parts.size() == 2) {
                    String op1 = unwrapBalancedParens(parts.get(0).trim());
                    String op2 = unwrapBalancedParens(parts.get(1).trim());
                    boolean op1Target = Pattern.compile("(?is)^" + targetPatternStr + "$")
                            .matcher(op1)
                            .matches();
                    boolean op2Target = Pattern.compile("(?is)^" + targetPatternStr + "$")
                            .matcher(op2)
                            .matches();
                    if ((op1Target && literalNumber.matcher(op2).matches())
                            || (op2Target && literalNumber.matcher(op1).matches())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String unwrapBalancedParens(String expr) {
        String s = expr.trim();
        while (s.startsWith("(") && s.endsWith(")") && isBalancedEnclosure(s)) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static boolean isBalancedEnclosure(String s) {
        int depth = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth <= 0) {
                    return false;
                }
            }
        }
        return depth == 1;
    }

    private static List<String> splitTopLevel(String expr, char delimiter) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == delimiter && depth == 0) {
                parts.add(expr.substring(start, i));
                start = i + 1;
            }
        }
        if (start < expr.length()) {
            parts.add(expr.substring(start));
        }
        return parts;
    }
}
