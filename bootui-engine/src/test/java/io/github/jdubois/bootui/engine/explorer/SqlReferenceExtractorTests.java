package io.github.jdubois.bootui.engine.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SqlReferenceExtractorTests {
    private final SqlReferenceExtractor extractor = new SqlReferenceExtractor();

    static Stream<Arguments> supported() {
        return Stream.of(
                Arguments.of(
                        "select * from orders o join public.lines l on o.id=l.order_id",
                        List.of("orders", "public.lines")),
                Arguments.of(
                        "SELECT 'from fake join secrets' FROM \"Sales\".\"Order\" AS o",
                        List.of("\"Sales\".\"Order\"")),
                Arguments.of(
                        "select * /* FROM wrong */ from [Order Details] -- join nope\n", List.of("[Order Details]")),
                Arguments.of("select * from `schema`.`Order``Line`", List.of("`schema`.`Order``Line`")),
                Arguments.of("insert into \"orders\" (id,name) values (1,'join bogus')", List.of("\"orders\"")),
                Arguments.of("update orders set name='FROM bogus' where id=1", List.of("orders")),
                Arguments.of("delete from public.orders where id=?", List.of("public.orders")),
                Arguments.of("select count(*) from products", List.of("products")),
                Arguments.of(
                        "SELECT o.id FROM public.orders o JOIN public.lines l ON l.order_id=o.id FOR UPDATE OF o, l",
                        List.of("public.orders", "public.lines")),
                Arguments.of("select * from orders o for share of o, other skip locked", List.of("orders")),
                Arguments.of("select * from orders order by id, name limit 10", List.of("orders")),
                Arguments.of("select * from orders limit 1, 10", List.of("orders")),
                Arguments.of("delete from orders returning id, name", List.of("orders")),
                Arguments.of("update \"IGNORE\" set status='new'", List.of("\"IGNORE\"")),
                Arguments.of("select o.id,l.id from orders o, lines l where l.id=o.id", List.of("orders", "lines")));
    }

    @ParameterizedTest
    @MethodSource("supported")
    void extractsOnlyIdentifiers(String sql, List<String> expected) {
        assertThat(extractor.extract(sql, 0)).isEqualTo(new SqlReferenceExtractor.Result(expected, "COMPLETE"));
    }

    @Test
    void neverClaimsCteSubqueryFunctionOrDollarQuotedTextAsATable() {
        for (String sql : List.of(
                "with chosen as (select * from orders) select * from chosen",
                "select * from (select * from orders) x",
                "select * from table_function(?)",
                "select $$ from secret $$ from orders")) {
            var result = extractor.extract(sql, 0);
            assertThat(result.identifiers()).isEmpty();
            assertThat(result.status()).isNotEqualTo("COMPLETE");
        }
    }

    @Test
    void unsupportedUpdateModifiersNeverBecomeTableNames() {
        for (String sql : List.of(
                "UPDATE IGNORE orders SET status='new' WHERE id=1",
                "UPDATE LOW_PRIORITY orders SET status='new'",
                "UPDATE LOW_PRIORITY IGNORE orders SET status='new'",
                "UPDATE TOP (10) orders SET status='new'",
                "UPDATE ONLY orders SET status='new' RETURNING id, status")) {
            assertThat(extractor.extract(sql, 0))
                    .as(sql)
                    .isEqualTo(new SqlReferenceExtractor.Result(List.of(), "PARTIAL"));
        }
    }

    @Test
    void truncationBatchesMalformedQuotesAndTokenBudgetRemainIncomplete() {
        assertThat(extractor
                        .extract("select * from orders; select * from others", 2)
                        .status())
                .isEqualTo("PARTIAL");
        assertThat(extractor.extract("select * from orders /* cut", 0).status()).isEqualTo("PARTIAL");
        assertThat(extractor.extract("select * from \"cut", 0).status()).isEqualTo("PARTIAL");
        assertThat(extractor.extract("select * from schema.", 0).identifiers()).isEmpty();
        assertThat(extractor.extract("select * from orders …", 0).status()).isEqualTo("PARTIAL");
        assertThat(extractor
                        .extract("select * from orders " + "join orders o on 1=1 ".repeat(3000), 0)
                        .status())
                .isEqualTo("PARTIAL");
    }
}
