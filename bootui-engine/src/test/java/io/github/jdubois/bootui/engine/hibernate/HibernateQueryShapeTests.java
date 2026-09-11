package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HibernateQueryShapeTests {

    @Test
    void recognizesTimestampAssignments() {
        for (String expression : List.of(
                "CURRENT_TIMESTAMP",
                "current_timestamp()",
                "CuRrEnT_TiMeStAmP ( )",
                "((current_timestamp()))",
                "current_timestamp /* database time */ ()")) {
            assertThat(maintainsVersion("update Order o set o.lastModified = " + expression, "o", "lastModified"))
                    .as("timestamp assignment: %s", expression)
                    .isTrue();
        }
    }

    @Test
    void recognizesDirectParameterAssignments() {
        for (String expression : List.of(":now", ":nextVersion", ":next_version2", "?1", "?12", "((:now))", "(?2)")) {
            assertThat(maintainsVersion("update Order o set o.lastModified = " + expression, "o", "lastModified"))
                    .as("parameter assignment: %s", expression)
                    .isTrue();
        }
    }

    @Test
    void recognizesParameterizedIncrements() {
        for (String expression : List.of(
                "o.version + :delta",
                ":delta + o.version",
                "o.version + ?1",
                "?2 + o.version",
                "((o.version) + (:delta))",
                "((?1) + (version))")) {
            assertThat(maintainsVersion("update Order o set o.version = " + expression, "o", "version"))
                    .as("parameterized increment: %s", expression)
                    .isTrue();
        }
    }

    @Test
    void preservesLiteralIncrements() {
        for (String expression : List.of("o.version + 1", "1L + o.version", "((version) + (2l))")) {
            assertThat(maintainsVersion("update Order o set o.version = " + expression, "o", "version"))
                    .as("literal increment: %s", expression)
                    .isTrue();
        }
    }

    @Test
    void matchesTheVersionAssignmentInTheSetClause() {
        for (String query : List.of(
                "update Order o set lastModified = :now where o.id = :id",
                "update Order o set o . lastModified = (:now) where o.id = :id",
                "update Order o set o.status = coalesce(:status, o.status), o.lastModified = :now",
                "update Order o set o.lastModified = :now, o.status = coalesce(:status, o.status)")) {
            assertThat(maintainsVersion(query, "o", "lastModified")).as(query).isTrue();
        }
        assertThat(maintainsVersion("update Order set lastModified = current_timestamp", null, "lastModified"))
                .isTrue();
    }

    @Test
    void rejectsSelfAssignmentsAndUnsupportedExpressions() {
        for (String expression : List.of(
                "o.version",
                "((o.version))",
                "version",
                "1",
                "null",
                "coalesce(o.version, :next)",
                "some_function()",
                "current_timestamp(1)",
                "current_timestamp + 1",
                "",
                ":",
                ":next trailing",
                "?",
                "?0",
                "?1suffix",
                "(current_timestamp()",
                "current_timestamp())",
                "o.version +",
                "o.version + :",
                "o.version + ?0",
                "o.version + :delta + 1")) {
            assertThat(maintainsVersion("update Order o set o.version = " + expression, "o", "version"))
                    .as("unsupported assignment: %s", expression)
                    .isFalse();
        }
    }

    @Test
    void ignoresOtherAttributesAliasesPredicatesAndQuotedText() {
        for (String query : List.of(
                "update Order o set o.status = :now",
                "update Order o set o.lastModifiedCopy = :now",
                "update Order o set other.lastModified = :now",
                "update Order o set o.status = :status where o.lastModified = :now",
                "update Order o set o.status = 'o.lastModified = CURRENT_TIMESTAMP'",
                "update Order o set o.status = :status /* o.lastModified = :now */",
                "update Order o set o.status = :status -- o.lastModified = :now\n",
                "update Order o set o.lastModified = 'CURRENT_TIMESTAMP'",
                "update Order o set o.lastModified = ':now'")) {
            assertThat(maintainsVersion(query, "o", "lastModified")).as(query).isFalse();
        }
    }

    @Test
    void requiresQueryAndVersionAttribute() {
        assertThat(maintainsVersion(null, "o", "version")).isFalse();
        assertThat(maintainsVersion("update Order o set o.version = :next", "o", null))
                .isFalse();
        assertThat(maintainsVersion("update Order o set o.version = :next", "o", " "))
                .isFalse();
    }

    private static boolean maintainsVersion(String query, String alias, String attribute) {
        return HibernateQueryShape.maintainsVersion(HibernateQueryShape.lexical(query), alias, attribute);
    }
}
