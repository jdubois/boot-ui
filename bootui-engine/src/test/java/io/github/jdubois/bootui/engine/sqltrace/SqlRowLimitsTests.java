package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.sqltrace.SqlRowLimits.Analysis;
import org.junit.jupiter.api.Test;

/** The outermost query block's row limit, offset, and ordering, across dialects, and what is never read. */
class SqlRowLimitsTests {

    private static Analysis analyze(String sql) {
        return SqlRowLimits.analyze(sql);
    }

    /** Whether the statement is the unordered page the observation reports, before its filter is considered. */
    private static boolean pagedWithoutOrder(String sql) {
        Analysis analysis = analyze(sql);
        return analysis.complete()
                && analysis.select()
                && analysis.rowLimit()
                && !analysis.orderBy()
                && !analysis.setOperation();
    }

    // ---- limits and offsets across dialects -------------------------------------------------------------------

    @Test
    void postgresqlLimitAndOffsetWithoutOrderBy() {
        Analysis analysis = analyze("select p1_0.id from owner p1_0 limit ? offset ?");

        assertThat(analysis.complete()).isTrue();
        assertThat(analysis.incompleteReason()).isNull();
        assertThat(analysis.select()).isTrue();
        assertThat(analysis.rowLimit()).isTrue();
        assertThat(analysis.offset()).isTrue();
        assertThat(analysis.orderBy()).isFalse();
    }

    @Test
    void postgresqlLimitAndOffsetWithOrderBy() {
        Analysis analysis = analyze("select p1_0.id from owner p1_0 order by p1_0.id limit ? offset ?");

        assertThat(analysis.rowLimit()).isTrue();
        assertThat(analysis.offset()).isTrue();
        assertThat(analysis.orderBy()).isTrue();
    }

    @Test
    void mySqlLimitOffsetCountFormIsAnOffset() {
        Analysis analysis = analyze("select id from owner limit ?, ?");

        assertThat(analysis.rowLimit()).isTrue();
        assertThat(analysis.offset()).isTrue();
        assertThat(analysis.orderBy()).isFalse();
    }

    @Test
    void mySqlLiteralOffsetZeroIsTheFirstPage() {
        Analysis analysis = analyze("select id from owner limit 0, 20");

        assertThat(analysis.rowLimit()).isTrue();
        assertThat(analysis.offset()).isFalse();
    }

    @Test
    void mySqlSingleRowCountWithAnOffsetIsNotAPage() {
        Analysis analysis = analyze("select id from owner limit 5, 1");

        assertThat(analysis.rowLimit()).isFalse();
        assertThat(analysis.offset()).isTrue();
    }

    @Test
    void aLiteralLimitOfOneIsASingleRowLookup() {
        assertThat(analyze("select id from owner where name like ? limit 1").rowLimit())
                .isFalse();
        assertThat(analyze("select id from owner limit ?").rowLimit()).isTrue();
        assertThat(analyze("select id from owner limit 10").rowLimit()).isTrue();
    }

    @Test
    void limitAllAndLimitNullDoNotLimit() {
        assertThat(analyze("select id from owner limit all").rowLimit()).isFalse();
        assertThat(analyze("select id from owner limit null offset ?").rowLimit())
                .isFalse();
    }

    @Test
    void fetchFirstRowsOnly() {
        assertThat(analyze("select id from owner fetch first ? rows only").rowLimit())
                .isTrue();
        assertThat(analyze("select id from owner fetch next 20 rows with ties").rowLimit())
                .isTrue();
        assertThat(analyze("select id from owner fetch first 1 row only").rowLimit())
                .isFalse();
        assertThat(analyze("select id from owner fetch first row only").rowLimit())
                .isFalse();
    }

    @Test
    void sqlServerOffsetFetchIsAPageWithAnOffset() {
        Analysis page = analyze("select id from owner order by id offset ? rows fetch next ? rows only");
        Analysis first = analyze("select id from owner offset 0 rows fetch next 10 rows only");

        assertThat(page.rowLimit()).isTrue();
        assertThat(page.offset()).isTrue();
        assertThat(page.orderBy()).isTrue();
        assertThat(first.rowLimit()).isTrue();
        assertThat(first.offset()).isFalse();
    }

    @Test
    void sqlServerTop() {
        assertThat(analyze("select top (10) id from owner").rowLimit()).isTrue();
        assertThat(analyze("select top 10 * from owner").rowLimit()).isTrue();
        assertThat(analyze("select top 10 * from owner").complete()).isTrue();
        assertThat(analyze("select top 10 id from owner").rowLimit()).isTrue();
        assertThat(analyze("select distinct top (?) id from owner").rowLimit()).isTrue();
        assertThat(analyze("select top 1 id from owner").rowLimit()).isFalse();
        assertThat(analyze("select top (1) id from owner").rowLimit()).isFalse();
        assertThat(analyze("select top (10) count(*) from owner").constantOrAggregateProjection())
                .isTrue();
    }

    @Test
    void aStatementWithoutALimitIsNotAPage() {
        Analysis analysis = analyze("select id from owner where id > ?");

        assertThat(analysis.complete()).isTrue();
        assertThat(analysis.rowLimit()).isFalse();
        assertThat(analysis.offset()).isFalse();
    }

    @Test
    void aColumnNamedOffsetIsNotAnOffset() {
        assertThat(analyze("select t.offset, t.limit from t").offset()).isFalse();
        assertThat(analyze("select t.offset from t order by t.offset").offset()).isFalse();
        assertThat(analyze("select id from t where offset = ?").offset()).isFalse();
    }

    @Test
    void keywordsAreReadCaseInsensitivelyAcrossLines() {
        Analysis analysis = analyze("SELECT ID FROM OWNER\nORDER\n  BY ID\nLIMIT 10 OFFSET 20");

        assertThat(analysis.orderBy()).isTrue();
        assertThat(analysis.rowLimit()).isTrue();
        assertThat(analysis.offset()).isTrue();
    }

    @Test
    void oracleRownumWrappersAreNotRead() {
        Analysis analysis = analyze("select * from (select o.*, rownum r from owner o) where r <= 10");

        assertThat(analysis.complete()).isTrue();
        assertThat(analysis.rowLimit()).isFalse();
    }

    // ---- the outermost block only -----------------------------------------------------------------------------

    @Test
    void anOrderByInASubqueryDoesNotOrderTheOuterQuery() {
        Analysis analysis = analyze("select * from (select id from owner order by id) o limit ?");

        assertThat(analysis.rowLimit()).isTrue();
        assertThat(analysis.orderBy()).isFalse();
    }

    @Test
    void anOrderByInAWindowDoesNotOrderTheOuterQuery() {
        Analysis analysis = analyze("select id, row_number() over (order by id) from owner limit ? offset ?");

        assertThat(analysis.rowLimit()).isTrue();
        assertThat(analysis.orderBy()).isFalse();
        assertThat(pagedWithoutOrder("select id, rank() over (partition by a order by b) from owner limit ?"))
                .isTrue();
    }

    @Test
    void aLimitInASubqueryIsNotTheOuterQuerysLimit() {
        Analysis analysis = analyze("select * from owner where id in (select id from pet limit ?)");

        assertThat(analysis.rowLimit()).isFalse();
        assertThat(analysis.offset()).isFalse();
    }

    @Test
    void aCommonTableExpressionIsReadThroughToItsFinalSelect() {
        Analysis outer = analyze("with o as (select id from owner order by id) select * from o limit ?");
        Analysis inner = analyze("with o as (select id from owner order by id limit ?) select * from o");
        Analysis recursive = analyze("with recursive t(n) as (select 1 union all select n + 1 from t where n < 5)"
                + " select n from t limit ?");

        assertThat(outer.select()).isTrue();
        assertThat(outer.rowLimit()).isTrue();
        assertThat(outer.orderBy()).isFalse();
        assertThat(inner.rowLimit()).isFalse();
        assertThat(recursive.rowLimit()).isTrue();
        assertThat(recursive.setOperation()).isFalse();
    }

    @Test
    void aCommonTableExpressionThatEndsInAWriteIsNotASelect() {
        Analysis analysis =
                analyze("with o as (select id from owner) delete from pet where owner_id in (select id from o)");

        assertThat(analysis.complete()).isTrue();
        assertThat(analysis.select()).isFalse();
    }

    @Test
    void setOperationsAreFlaggedForTheCallerToSkip() {
        assertThat(analyze("select id from a union select id from b limit ?").setOperation())
                .isTrue();
        assertThat(analyze("select id from a intersect select id from b").setOperation())
                .isTrue();
        assertThat(analyze("select id from a except select id from b").setOperation())
                .isTrue();
        assertThat(analyze("select id from a where id in (select id from b union select id from c) limit ?")
                        .setOperation())
                .isFalse();
    }

    // ---- comments, strings, quoted names, dollar quoting ------------------------------------------------------

    @Test
    void keywordsInCommentsAreIgnored() {
        assertThat(analyze("select id from owner /* order by id */ limit ?").orderBy())
                .isFalse();
        assertThat(analyze("select id from owner -- order by id\n limit ?").orderBy())
                .isFalse();
        assertThat(analyze("select id from owner order by id /* limit ? */").rowLimit())
                .isFalse();
        assertThat(analyze("select id from owner -- limit ?\n").rowLimit()).isFalse();
    }

    @Test
    void mySqlHashCommentsHideAndFakeKeywords() {
        Analysis hidden = analyze("select id from owner # order by id\n limit ? offset ?");
        Analysis faked = analyze("select id from owner order by id # limit ?\n");

        assertThat(hidden.complete()).isTrue();
        assertThat(hidden.orderBy()).isFalse();
        assertThat(hidden.rowLimit()).isTrue();
        assertThat(faked.complete()).isTrue();
        assertThat(faked.orderBy()).isTrue();
        assertThat(faked.rowLimit()).isFalse();
    }

    @Test
    void keywordsInStringsAndQuotedNamesAreIgnored() {
        assertThat(analyze("select 'order by id limit 5' from owner").rowLimit())
                .isFalse();
        assertThat(analyze("select \"limit\", \"order\" from owner").rowLimit()).isFalse();
        assertThat(analyze("select `limit` from owner").rowLimit()).isFalse();
        assertThat(analyze("select [limit] from owner").rowLimit()).isFalse();
        assertThat(analyze("select id from owner where note = 'a limit ? offset ?' limit ?")
                        .offset())
                .isFalse();
        assertThat(analyze("select id from owner where note = 'order by x' limit ?")
                        .orderBy())
                .isFalse();
    }

    @Test
    void keywordsInDollarQuotedBodiesAreIgnored() {
        assertThat(analyze("select $$ limit 5 order by x $$ from owner").rowLimit())
                .isFalse();
        assertThat(analyze("select $body$ order by x $body$ from owner limit ?").orderBy())
                .isFalse();
        assertThat(pagedWithoutOrder("select $1 from owner limit $2")).isTrue();
    }

    // ---- projection and where ---------------------------------------------------------------------------------

    @Test
    void constantAndAggregateProjections() {
        assertThat(analyze("select count(*) from owner limit ?").constantOrAggregateProjection())
                .isTrue();
        assertThat(analyze("select 1 from owner limit 1").constantOrAggregateProjection())
                .isTrue();
        assertThat(analyze("select exists (select 1 from owner where id = ?)").constantOrAggregateProjection())
                .isTrue();
        assertThat(analyze("select distinct 1 from owner limit ?").constantOrAggregateProjection())
                .isTrue();
        assertThat(analyze("select id, count(*) from owner group by id limit ?").constantOrAggregateProjection())
                .isFalse();
        assertThat(analyze("select * from owner limit ?").constantOrAggregateProjection())
                .isFalse();
    }

    @Test
    void theExistsByShapeIsAnEqualityOnlyLookup() {
        Analysis analysis = analyze("select c1_0.id from c c1_0 where c1_0.a=? and c1_0.b=? limit ?");

        assertThat(analysis.rowLimit()).isTrue();
        assertThat(analysis.offset()).isFalse();
        assertThat(analysis.equalityOnlyWhere()).isTrue();
    }

    @Test
    void equalityTermsAcceptBindsAndLiteralsOfEveryStyle() {
        assertThat(analyze("select id from t where a = ? limit ?").equalityOnlyWhere())
                .isTrue();
        assertThat(analyze("select id from t where a = ?1 and b = ?2 limit ?").equalityOnlyWhere())
                .isTrue();
        assertThat(analyze("select id from t where a = $1 and b = $2 limit $3").equalityOnlyWhere())
                .isTrue();
        assertThat(analyze("select id from t where a = :a and b = @b limit ?").equalityOnlyWhere())
                .isTrue();
        assertThat(analyze("select id from t where \"a\" = 5 and t.active = true and n = 'x' limit ?")
                        .equalityOnlyWhere())
                .isTrue();
        assertThat(analyze("select id from t where a = ?::uuid order by id").equalityOnlyWhere())
                .isTrue();
    }

    @Test
    void anythingButEqualityTermsIsNotEqualityOnly() {
        for (String where : new String[] {
            "a = ? or b = ?",
            "a in (?, ?)",
            "a like ?",
            "a between ? and ?",
            "a < ?",
            "a >= ?",
            "a <> ?",
            "a is null",
            "not a = ?",
            "a = ? and b is not null",
            "a = (select max(id) from u)",
            "a = b",
            "lower(a) = ?",
            "exists (select 1 from u)",
            "a = ? and (b = ? or c = ?)"
        }) {
            assertThat(analyze("select id from t where " + where + " limit ?").equalityOnlyWhere())
                    .as(where)
                    .isFalse();
        }
        assertThat(analyze("select id from t limit ?").equalityOnlyWhere()).isFalse();
    }

    @Test
    void orFilterWithALimitIsNotALookup() {
        Analysis analysis = analyze("select id from t where a=? or b=? limit ?");

        assertThat(analysis.rowLimit()).isTrue();
        assertThat(analysis.equalityOnlyWhere()).isFalse();
    }

    // ---- what is never read -----------------------------------------------------------------------------------

    @Test
    void truncatedStatementsAreIncompleteAndNeverLackAnOrderBy() {
        Analysis analysis = analyze("select id from owner where name like ? limit ? offset ?…");

        assertThat(analysis.complete()).isFalse();
        assertThat(analysis.incompleteReason()).contains("shortened");
        assertThat(analysis.select()).isTrue();
        assertThat(analysis.rowLimit()).isFalse();
        assertThat(analysis.orderBy()).isFalse();
    }

    @Test
    void statementsLongerThanTheScanLimitAreIncomplete() {
        String sql = "select id from owner where note = '" + "x".repeat(SqlStatementNormalizer.MAX_SCAN_LENGTH)
                + "' limit ?";

        Analysis analysis = analyze(sql);

        assertThat(analysis.complete()).isFalse();
        assertThat(analysis.incompleteReason()).contains("longer than");
        assertThat(analysis.rowLimit()).isFalse();
    }

    @Test
    void unclosedQuotesAndCommentsAreIncomplete() {
        for (String sql : new String[] {
            "select id from owner where name = 'abc limit ?",
            "select id from owner /* order by id limit ?",
            "select id from owner where \"name limit ?",
            "select $$ never closed limit ? from owner",
            "select [name limit ? from owner",
            "select id from owner where note = 'a''"
        }) {
            assertThat(analyze(sql).complete()).as(sql).isFalse();
            assertThat(analyze(sql).incompleteReason()).as(sql).isNotBlank();
        }
    }

    @Test
    void dialectSpecificTextTheLexerCannotClassifyIsIncomplete() {
        for (String sql : new String[] {
            "select data #>> '{a}' from owner limit ?",
            "select * from #temp limit 5",
            "select id from owner where note = 'a\\' limit ?",
            "select /*! straight_join */ id from owner limit ?",
            "select id from owner /* a /* nested */ order by id */ limit ?",
            "select id from owner limit ?; select id from pet",
            "select id from (owner limit ?",
            "select id from owner) limit ?",
            "(select id from a) union (select id from b)",
            "with x as (select 1) ",
            "select id from t where flags # 1 = 0 order by id limit 10",
            "select id from t where flags # mask = 0\n order by id\n limit 10",
            "select id from t where note like \"a\\\" LIMIT 10 \\\"b\"",
            "select id from t where note like q'[it's LIMIT 10 and it's fine]'",
            "select id from t /*M! ORDER BY id */ limit 10",
            "select id from t limit ((1))+1",
            "select id from t limit (2 - 1)",
            "select id from t limit (a, b)",
            "select top (100) percent id from t",
            "select top (n + 1) id from t",
            "select id from t offset 0 rows fetch next (n + 1) rows only",
            "select id from t offset 0 rows fetch next 5 widgets"
        }) {
            Analysis analysis = analyze(sql);
            assertThat(analysis.complete()).as(sql).isFalse();
            assertThat(analysis.rowLimit()).as(sql).isFalse();
            assertThat(analysis.orderBy()).as(sql).isFalse();
        }
    }

    @Test
    void aTrailingSemicolonIsTheSameStatement() {
        Analysis analysis = analyze("select id from owner limit ?;");

        assertThat(analysis.complete()).isTrue();
        assertThat(analysis.rowLimit()).isTrue();
    }

    @Test
    void blankAndMissingStatementsAreIncomplete() {
        assertThat(analyze(null).complete()).isFalse();
        assertThat(analyze("   ").complete()).isFalse();
        assertThat(analyze("-- only a comment").complete()).isFalse();
        assertThat(analyze(null).select()).isFalse();
    }

    @Test
    void statementsThatAreNotSelectsAreCompleteAndNotCounted() {
        for (String sql : new String[] {
            "insert into owner (name) values (?)",
            "update owner set name = ? where id = ?",
            "delete from owner where id = ?",
            "show tables",
            "call refresh()",
            "set search_path = public"
        }) {
            Analysis analysis = analyze(sql);
            assertThat(analysis.complete()).as(sql).isTrue();
            assertThat(analysis.select()).as(sql).isFalse();
            assertThat(analysis.rowLimit()).as(sql).isFalse();
        }
    }

    @Test
    void aStatementTheNormalizerLexesTheSameWayHidesNothingFromThisReader() {
        String sql = "select id from owner where name = 'order by x' /* limit 3 */ order by id limit ?";

        Analysis analysis = analyze(sql);

        assertThat(analysis.orderBy()).isTrue();
        assertThat(analysis.rowLimit()).isTrue();
        assertThat(SqlStatementNormalizer.normalize(sql).sql())
                .isEqualTo("select id from owner where name = ? order by id limit ?");
    }

    @Test
    void anEmptyHashCommentEndsAtItsLineAndAtTheEndOfTheText() {
        Analysis trailing = analyze("select id from owner limit ? #");
        Analysis empty = analyze("select id from owner #\norder by id\nlimit ? offset ?");
        int[] ranges = SqlStatementNormalizer.ranges("select 1 #");

        assertThat(trailing.complete()).isTrue();
        assertThat(trailing.rowLimit()).isTrue();
        assertThat(empty.complete()).isTrue();
        assertThat(empty.orderBy()).isTrue();
        assertThat(ranges[ranges.length - 2]).isEqualTo("select 1 #".length());
        assertThat(SqlStatementNormalizer.normalize("select id #\nfrom owner").sql())
                .isEqualTo("select id from owner");
    }

    @Test
    void aHashCommentThatReadsLikeProseIsAComment() {
        Analysis analysis = analyze("select id from owner # the page of owners\n limit ? offset ?");

        assertThat(analysis.complete()).isTrue();
        assertThat(analysis.rowLimit()).isTrue();
        assertThat(analysis.orderBy()).isFalse();
    }

    @Test
    void equalityTermsMayBeGroupedAndSigned() {
        assertThat(analyze("select id from t where (id = ?) limit ?").equalityOnlyWhere())
                .isTrue();
        assertThat(analyze("select id from t where (a = ? and b = ?) and c = ? limit ?")
                        .equalityOnlyWhere())
                .isTrue();
        assertThat(analyze("select id from t where id = -1 limit ?").equalityOnlyWhere())
                .isTrue();
        assertThat(analyze("select id from t where (a = ? or b = ?) limit ?").equalityOnlyWhere())
                .isFalse();
        assertThat(analyze("select id from t where (a = ? limit ?").complete()).isFalse();
        assertThat(analyze("select id from t where id = -a limit ?").equalityOnlyWhere())
                .isFalse();
    }

    @Test
    void parenthesisedAndNullProjectionsAreConstantOrAggregate() {
        for (String projection : new String[] {"(count(*))", "(1)", "null", "((count(*)))", "(exists (select 1))"}) {
            assertThat(analyze("select " + projection + " from owner limit ?").constantOrAggregateProjection())
                    .as(projection)
                    .isTrue();
        }
        assertThat(analyze("select (a + b) from owner limit ?").constantOrAggregateProjection())
                .isFalse();
    }

    @Test
    void aLiteralOneInParenthesesIsStillASingleRow() {
        assertThat(analyze("select id from t where a > ? limit ((1))").rowLimit())
                .isFalse();
        assertThat(analyze("select id from t offset (0) rows fetch next (10) rows only")
                        .offset())
                .isFalse();
        assertThat(analyze("select id from t offset (0) rows fetch next (10) rows only")
                        .rowLimit())
                .isTrue();
    }

    @Test
    void textAfterANonAsciiCharacterIsLexedAsTheNormalizerDoes() {
        String sql = "select\u2003id from t where a = \u20ac1 limit ?";

        assertThat(SqlStatementNormalizer.normalize(sql).sql()).isEqualTo("select id from t where a = \u20ac? limit ?");
        assertThat(analyze(sql).rowLimit()).isTrue();
    }

    @Test
    void aHashDirectlyAfterANameIsNotAComment() {
        Analysis analysis = analyze("select e.emp# from emp e where e.dept# is null order by e.emp#\nlimit ?");

        assertThat(analysis.complete()).isFalse();
        assertThat(analysis.orderBy()).isFalse();
        assertThat(analysis.rowLimit()).isFalse();
    }

    @Test
    void orderSiblingsByIsAnOrderBy() {
        Analysis analysis =
                analyze("select name from emp start with mgr is null connect by prior id = mgr order siblings by name"
                        + " fetch first ? rows only");

        assertThat(analysis.orderBy()).isTrue();
        assertThat(analysis.rowLimit()).isTrue();
    }

    @Test
    void aLeadingCommentDoesNotHideATruncatedQuery() {
        Analysis analysis = analyze("/* generated */ select id from owner limit ? offset ?…");

        assertThat(analysis.complete()).isFalse();
        assertThat(analysis.select()).isTrue();
    }
}
