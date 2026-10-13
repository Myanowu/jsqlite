package com.myano.jsqlite.admin;

import com.myano.jsqlite.Database;
import com.myano.jsqlite.sql.Parser;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.IndexDef;
import com.myano.jsqlite.store.TableInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlFormsTest {
  private final Parser parser = new Parser();

  @Test
  void generatedStatementsQuoteNamesAndStayExecutable() {
    Column key = new Column("Key", ColumnType.TEXT, true, true, false, false, false, null);
    Column note = new Column("Note", ColumnType.TEXT, false, false, false, false, false, null);
    TableInfo grants = new TableInfo("PersistedGrants", List.of(key, note), 0, null, List.of("Key"));
    String select = SqlForms.selectColumns(grants);
    String insert = SqlForms.insert(grants);
    String update = SqlForms.update(grants);
    String delete = SqlForms.delete(grants);
    assertTrue(select.contains("\"Key\""));
    assertTrue(insert.contains("''"));
    assertTrue(insert.contains("NULL"));
    assertTrue(update.contains("SET"));
    assertTrue(update.contains("WHERE \"Key\" = ''"));
    assertTrue(delete.contains("WHERE \"Key\" = ''"));
    parser.parse(select);
    parser.parse(insert);
    parser.parse(update);
    parser.parse(delete);
    parser.parse(SqlForms.create(grants));
    parser.parse(SqlForms.filterColumn(grants, note));
    parser.parse(SqlForms.orderBy(grants, note));
  }

  @Test
  void integerPrimaryKeyInsertLeavesTheIdEmpty() {
    Column id = new Column("Id", ColumnType.INTEGER, true, true, true, false, false, null);
    Column userId = new Column("UserId", ColumnType.TEXT, true, false, false, false, false, null);
    TableInfo claims = new TableInfo("UserClaims", List.of(id, userId), 0, null, List.of("Id"));
    String insert = SqlForms.insert(claims);
    assertTrue(insert.contains("\"Id\",\n  \"UserId\"") || insert.contains("\"Id\",\r\n  \"UserId\""));
    assertTrue(insert.contains("NULL,\n  ''") || insert.contains("NULL,\r\n  ''"));
    parser.parse(insert);
    parser.parse(SqlForms.countRows(claims));
  }

  @Test
  void sampleDatabaseKeepsCreateTextAndPartialIndex() {
    try (Database db = Database.open(Path.of("src/test/resources/sample.sqlite"))) {
      TableInfo users = db.tables().stream().filter(table -> table.name().equals("Users")).findFirst().orElseThrow();
      assertEquals(List.of("Id"), users.primaryKey());
      String create = SqlForms.create(users);
      assertTrue(create.contains("PRIMARY KEY"));
      parser.parse(create);
      parser.parse(SqlForms.selectAll(users));
      IndexDef index = db.indexes().stream()
          .filter(item -> item.name().equals("UserNameIndex"))
          .findFirst()
          .orElseThrow();
      String indexSql = SqlForms.createIndex(index);
      assertTrue(indexSql.contains("UNIQUE"));
      assertTrue(indexSql.contains("IS NOT NULL"));
      parser.parse(indexSql);
    }
  }
}
