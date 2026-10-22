package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlStatementNormalizerTests {

    @Test
    void replacesStringAndNumericLiteralsSoEquivalentExecutionsAggregate() {
        String first = SqlStatementNormalizer.fingerprintOf("select * from users where id = 41 and name = 'ada'");
        String second = SqlStatementNormalizer.fingerprintOf("select * from users where id = 42 and name = 'grace'");

        assertThat(first).isEqualTo(second).isEqualTo("select * from users where id = ? and name = ?");
    }

    @Test
    void neverLeavesALiteralValueInTheNormalizedText() {
        SqlStatementNormalizer.Result result = SqlStatementNormalizer.normalize(
                "insert into audit (actor, token) values ('ada@example.com', 'sk-live-9f3b')");

        assertThat(result.sql()).doesNotContain("ada@example.com").doesNotContain("sk-live-9f3b");
        assertThat(result.literalCount()).isEqualTo(2);
    }

    @Test
    void foldsInListsSoListLengthDoesNotSplitOneStatementIntoMany() {
        String three = SqlStatementNormalizer.fingerprintOf("select * from t where id in (1, 2, 3)");
        String thirty = SqlStatementNormalizer.fingerprintOf("select * from t where id in (1,2,3,4,5,6,7,8,9,10)");

        assertThat(three).isEqualTo(thirty).isEqualTo("select * from t where id in (?)");
    }

    @Test
    void isCaseInsensitiveForGroupingButKeepsTheStatementAsWritten() {
        SqlStatementNormalizer.Result upper = SqlStatementNormalizer.normalize("SELECT ID FROM USERS");
        SqlStatementNormalizer.Result lower = SqlStatementNormalizer.normalize("select id from users");

        assertThat(upper.fingerprint()).isEqualTo(lower.fingerprint());
        assertThat(upper.sql()).isEqualTo("SELECT ID FROM USERS");
    }

    @Test
    void keepsQuotedIdentifiersSoDifferentColumnsNeverCollapse() {
        String first = SqlStatementNormalizer.fingerprintOf("select \"firstName\" from \"user\"");
        String second = SqlStatementNormalizer.fingerprintOf("select \"lastName\" from \"user\"");

        assertThat(first).isNotEqualTo(second);
        assertThat(SqlStatementNormalizer.normalize("select \"firstName\" from \"user\"")
                        .sql())
                .contains("\"firstName\"");
    }

    @Test
    void doesNotTreatDigitsInsideIdentifiersAsLiterals() {
        SqlStatementNormalizer.Result result =
                SqlStatementNormalizer.normalize("select t2.column1 from order_2024 t2 where t2.column1 = 7");

        assertThat(result.sql()).isEqualTo("select t2.column1 from order_2024 t2 where t2.column1 = ?");
        assertThat(result.literalCount()).isEqualTo(1);
    }

    @Test
    void collapsesWhitespaceAndStripsComments() {
        SqlStatementNormalizer.Result result = SqlStatementNormalizer.normalize(
                "select id\n  from users -- the comment\n  where id = 1 /* inline */ and active = true");

        assertThat(result.sql()).isEqualTo("select id from users where id = ? and active = ?");
    }

    @Test
    void countsLiteralsInPredicatePositionsSeparately() {
        assertThat(SqlStatementNormalizer.normalize("select * from t where id = 42")
                        .predicateLiteralCount())
                .isEqualTo(1);
        assertThat(SqlStatementNormalizer.normalize("select * from t where id in (1, 2)")
                        .predicateLiteralCount())
                .isEqualTo(2);
        assertThat(SqlStatementNormalizer.normalize("select * from t where name like 'a%'")
                        .predicateLiteralCount())
                .isEqualTo(1);
        assertThat(SqlStatementNormalizer.normalize("select * from t where age >= 18")
                        .predicateLiteralCount())
                .isEqualTo(1);
    }

    @Test
    void doesNotCountProjectedOrPagingLiteralsAsPredicateLiterals() {
        assertThat(SqlStatementNormalizer.normalize("select 1, 'literal' from dual")
                        .predicateLiteralCount())
                .isZero();
        assertThat(SqlStatementNormalizer.normalize("select * from t limit 50 offset 100")
                        .predicateLiteralCount())
                .isZero();
        assertThat(SqlStatementNormalizer.normalize("select cast(x as decimal(10, 2)) from t")
                        .predicateLiteralCount())
                .isZero();
    }

    @Test
    void survivesUnterminatedQuotesFromTruncatedCapture() {
        SqlStatementNormalizer.Result result = SqlStatementNormalizer.normalize("select * from t where name = 'ad");

        assertThat(result.sql()).isEqualTo("select * from t where name = ?");
    }

    @Test
    void masksUnterminatedQuotedValuesFromTruncatedCapture() {
        assertThat(SqlStatementNormalizer.normalize("select * from t where name = \"Ada Lovelace")
                        .sql())
                .isEqualTo("select * from t where name = ?");
        assertThat(SqlStatementNormalizer.normalize("select * from t where note = $$sëcrét")
                        .sql())
                .isEqualTo("select * from t where note = ?");
        assertThat(SqlStatementNormalizer.normalize("select * from t where note = $body$sëcrét")
                        .sql())
                .isEqualTo("select * from t where note = ?");
    }

    @Test
    void handlesDoubledQuoteEscapesWithoutSplittingTheLiteral() {
        SqlStatementNormalizer.Result result =
                SqlStatementNormalizer.normalize("select * from t where name = 'it''s' and id = 1");

        assertThat(result.sql()).isEqualTo("select * from t where name = ? and id = ?");
        assertThat(result.literalCount()).isEqualTo(2);
    }

    @Test
    void boundsTheScanSoAPathologicalStatementCannotDominate() {
        String huge = "select * from t where x in (" + "1,".repeat(30_000) + "1)";

        SqlStatementNormalizer.Result result = SqlStatementNormalizer.normalize(huge);

        assertThat(result.sql().length()).isLessThan(1_000);
    }

    @Test
    void masksPostgresDollarQuotedStrings() {
        SqlStatementNormalizer.Result result =
                SqlStatementNormalizer.normalize("select * from t where note = $$top secret$$ and tag = $x$vip$x$");

        assertThat(result.sql()).isEqualTo("select * from t where note = ? and tag = ?");
        assertThat(result.sql()).doesNotContain("secret").doesNotContain("vip");
        assertThat(result.predicateLiteralCount()).isEqualTo(2);
    }

    @Test
    void leavesDollarSignsInsideIdentifiersAndBindParametersAlone() {
        SqlStatementNormalizer.Result result = SqlStatementNormalizer.normalize("select a$b$c from t where id = 5");

        assertThat(result.sql()).isEqualTo("select a$b$c from t where id = ?");
        assertThat(SqlStatementNormalizer.normalize("select * from t where id = $12")
                        .sql())
                .isEqualTo("select * from t where id = $12");
        assertThat(SqlStatementNormalizer.normalize("select a\u0301$body$column from orders where id = 1")
                        .sql())
                .isEqualTo("select a\u0301$body$column from orders where id = ?");
    }

    @Test
    void masksADoubleQuotedRunThatCannotBeAnIdentifier() {
        SqlStatementNormalizer.Result masked =
                SqlStatementNormalizer.normalize("select * from t where name = \"Ada Lovelace\"");
        SqlStatementNormalizer.Result identifier =
                SqlStatementNormalizer.normalize("select \"firstName\" from t where id = 1");

        assertThat(masked.sql()).isEqualTo("select * from t where name = ?");
        assertThat(masked.sql()).doesNotContain("Ada");
        assertThat(identifier.sql()).isEqualTo("select \"firstName\" from t where id = ?");
    }

    @Test
    void normalizesBooleanLiteralsSoEquivalentPredicatesAggregate() {
        assertThat(SqlStatementNormalizer.normalize("select * from t where active = true")
                        .fingerprint())
                .isEqualTo(SqlStatementNormalizer.normalize("select * from t where active = FALSE")
                        .fingerprint());
    }

    @Test
    void collapsesInListsButNotProjectionsOrArguments() {
        assertThat(SqlStatementNormalizer.normalize("select * from t where id in (1, 2, 3)")
                        .sql())
                .isEqualTo("select * from t where id in (?)");
        assertThat(SqlStatementNormalizer.normalize("select 1, 2 from dual").fingerprint())
                .isNotEqualTo(
                        SqlStatementNormalizer.normalize("select 1 from dual").fingerprint());
        assertThat(SqlStatementNormalizer.normalize("insert into t (a, b) values (1, 2)")
                        .sql())
                .isEqualTo("insert into t (a, b) values (?, ?)");
    }

    @Test
    void returnsAnEmptyResultForNullOrBlankSql() {
        assertThat(SqlStatementNormalizer.normalize(null).fingerprint()).isEmpty();
        assertThat(SqlStatementNormalizer.normalize("   ").fingerprint()).isEmpty();
    }

    @Test
    void aMySqlHashCommentIsDroppedLikeADashComment() {
        assertThat(SqlStatementNormalizer.normalize("select id # the owner 42\nfrom owner")
                        .sql())
                .isEqualTo("select id from owner");
        assertThat(SqlStatementNormalizer.normalize("select id from owner #").sql())
                .isEqualTo("select id from owner");
    }

    @Test
    void aHashThatIsNotACommentStaysInTheStatement() {
        assertThat(SqlStatementNormalizer.normalize("select data #>> '{a}' from t")
                        .sql())
                .isEqualTo("select data #>> ? from t");
        assertThat(SqlStatementNormalizer.normalize("select * from #temp").sql())
                .isEqualTo("select * from #temp");
    }

    @Test
    void tokensClassifyTheTextTheWayNormalizeLexesIt() {
        String sql = "select \"a\", 'x' /* c */ from t1 where n = 5 and f = ? -- end";

        int[] tokens = SqlStatementNormalizer.tokens(sql);
        StringBuilder kinds = new StringBuilder();
        for (int i = 0; i < tokens.length; i += 3) {
            kinds.append(tokens[i + 2]);
        }

        assertThat(sql.substring(tokens[0], tokens[1])).isEqualTo("select");
        assertThat(kinds.toString())
                .isEqualTo(
                        "1" // select
                                + "3" // "a"
                                + "5" // ,
                                + "2" // 'x'
                                + "4" // /* c */
                                + "1" // from
                                + "1" // t1
                                + "1" // where
                                + "1" // n
                                + "5" // =
                                + "2" // 5
                                + "1" // and
                                + "1" // f
                                + "5" // =
                                + "5" // ?
                                + "4"); // -- end
        assertThat(SqlStatementNormalizer.tokens(null)).isEmpty();
    }

    @Test
    void tokensDoNotChangeWhatNormalizeReturns() {
        String sql = "select a1, $1, t.b2 from t where x = true and y = 1.5e3 and z = $$q$$";

        SqlStatementNormalizer.tokens(sql);

        assertThat(SqlStatementNormalizer.normalize(sql).sql())
                .isEqualTo("select a1, $1, t.b2 from t where x = ? and y = ? and z = ?");
    }
}
