package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaIntrospectorTests {

    @Test
    void readForeignKeysGroupsAnUnnamedCompositeForeignKeyIntoOneConstraint() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(rs);
        // Simulates a driver reporting an unnamed composite foreign key (child.a, child.b) -> parent(a, b)
        // followed by a second, unrelated unnamed single-column foreign key on the same table.
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getString("FK_NAME")).thenReturn(null, null, null);
        when(rs.getShort("KEY_SEQ")).thenReturn((short) 1, (short) 2, (short) 1);
        when(rs.getString("FKCOLUMN_NAME")).thenReturn("A", "B", "C");
        when(rs.getString("PKTABLE_NAME")).thenReturn("parent", "parent", "other");

        List<ForeignKeyModel> foreignKeys = SchemaIntrospector.readForeignKeys(metaData, "cat", "schema", "child");

        assertThat(foreignKeys).hasSize(2);
        assertThat(foreignKeys.get(0).columns()).containsExactly("A", "B");
        assertThat(foreignKeys.get(0).referencedTable()).isEqualTo("parent");
        assertThat(foreignKeys.get(1).columns()).containsExactly("C");
        assertThat(foreignKeys.get(1).referencedTable()).isEqualTo("other");
    }

    @Test
    void readForeignKeysKeepsNamedForeignKeysGroupedByName() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("FK_NAME")).thenReturn("fk_child_parent", "fk_child_parent");
        when(rs.getShort("KEY_SEQ")).thenReturn((short) 1, (short) 2);
        when(rs.getString("FKCOLUMN_NAME")).thenReturn("A", "B");
        when(rs.getString("PKTABLE_NAME")).thenReturn("parent", "parent");

        List<ForeignKeyModel> foreignKeys = SchemaIntrospector.readForeignKeys(metaData, "cat", "schema", "child");

        assertThat(foreignKeys).hasSize(1);
        assertThat(foreignKeys.get(0).name()).isEqualTo("fk_child_parent");
        assertThat(foreignKeys.get(0).columns()).containsExactly("A", "B");
    }
}
