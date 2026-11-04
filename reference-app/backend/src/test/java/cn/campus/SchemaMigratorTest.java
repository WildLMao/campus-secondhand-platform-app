package cn.campus;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaMigratorTest {
    @Test
    void splitsStatementsAndSkipsComments() {
        String script = """
                -- 注释中的分号; 不拆分为语句
                CREATE TABLE a (id VARCHAR(10));
                /* 块注释; 同样忽略 */ CREATE INDEX idx_a ON a (id);
                INSERT INTO a VALUES ('x;y');
                """;
        List<String> parts = SchemaMigrator.split(script);
        assertEquals(3, parts.size());
        assertTrue(parts.get(0).startsWith("CREATE TABLE a"));
        assertTrue(parts.get(1).startsWith("CREATE INDEX idx_a"));
        assertEquals("INSERT INTO a VALUES ('x;y')", parts.get(2));
    }

    @Test
    void keepsStatementWithoutTrailingSemicolon() {
        assertEquals(List.of("CREATE TABLE t (id INT)"), SchemaMigrator.split("CREATE TABLE t (id INT)"));
    }

    @Test
    void checksumIgnoresLineEndingDifferences() throws Exception {
        assertEquals(SchemaMigrator.checksum("a\r\nb\r\n"), SchemaMigrator.checksum("a\nb\n"));
        assertNotEquals(SchemaMigrator.checksum("a\nb"), SchemaMigrator.checksum("a\nb "));
    }

    @Test
    void maxVersionUsesNumericOrder() {
        assertEquals(10, SchemaMigrator.maxVersion(Map.of(2L, "x", 10L, "y")));
        assertEquals(0, SchemaMigrator.maxVersion(Map.of()));
    }
}
