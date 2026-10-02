package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SqlShapesTests {

    @AfterEach
    void clear() {
        SqlShapes.clear();
    }

    @Test
    void aStatementsShapeMatchesTheNormalizerAndTheTableParser() {
        String sql = "select * from orders o join lines l on l.order_id = o.id where o.id = 42";

        assertThat(SqlShapes.fingerprint(sql)).isEqualTo(SqlStatementNormalizer.fingerprintOf(sql));
        assertThat(SqlShapes.tables(sql)).isEqualTo(SqlTables.of(sql));
        assertThat(SqlShapes.fingerprint(sql)).isSameAs(SqlShapes.fingerprint(sql));
        assertThatThrownBy(() -> SqlShapes.tables(sql).add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theCacheIsBoundedAndClearedWithTheRun() {
        for (int i = 0; i < SqlShapes.MAX_ENTRIES + 10; i++) {
            SqlShapes.fingerprint("select " + i + " from t" + i);
        }
        assertThat(SqlShapes.size()).isLessThanOrEqualTo(SqlShapes.MAX_ENTRIES);

        SqlShapes.clear();
        assertThat(SqlShapes.size()).isZero();
        assertThat(SqlShapes.fingerprint(null)).isEqualTo(SqlStatementNormalizer.fingerprintOf(null));
    }
}
