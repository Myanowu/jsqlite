# JSQLite

Pure-Java SQLite-compatible database engine and JDBC driver. It creates and opens SQLite 3 files from Java, with no native library and no JNI.

| | |
| --- | --- |
| Driver class | `com.myano.jsqlite.JDBC` |
| JDBC URLs | `jdbc:jsqlite:<path>` and `jdbc:sqlite:<path>` |
| Coordinates | `com.myano:jsqlite:0.1.0-SNAPSHOT` |
| Language level | Java 21 |

It is aimed at Java applications that want a SQLite-format file and a JDBC API. SQL and JDBC coverage is a subset of SQLite and of the JDBC specification. `Driver.jdbcCompliant()` returns `false`.

## Requirements

- JDK 21
- Maven 3

Tests use JUnit 5. `org.xerial:sqlite-jdbc` is a test-scoped dependency used by compatibility tests. It is not required at runtime.

## Build

```bash
mvn test
mvn package
mvn install
```

`mvn package` writes `target/jsqlite-0.1.0-SNAPSHOT.jar` and copies it to `dist/jsqlite.jar`. The jar main class is the Swing browser `com.myano.jsqlite.admin.DbAdmin`. `dist/` is gitignored.

The `jdk8` Maven profile sets the compiler release to 8 and compiles `src18/main/java`. That tree is gitignored and is not part of a clone. When it is present locally:

```bash
mvn -Pjdk8 -DskipTests package
```

The profile skips tests and writes `dist/jsqlite-jdk8.jar` (Maven classifier `jdk8`).

## Maven dependency

This repository does not publish the artifact. Install it into the local repository with `mvn install`, then depend on the snapshot:

```xml
<dependency>
  <groupId>com.myano</groupId>
  <artifactId>jsqlite</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`0.1.0-SNAPSHOT` is a development version. Behavior can change between installs.

## JDBC usage

`com.myano.jsqlite.JDBC` registers itself with `DriverManager` when the class is loaded. The same class is listed in `META-INF/services/java.sql.Driver`, so a jar on the classpath is discovered as a JDBC 4 driver. `Class.forName("com.myano.jsqlite.JDBC")` loads it explicitly.

A URL whose path does not exist creates a new SQLite 3 file (UTF-8, 4096-byte pages). A path that is already a SQLite 3 file is opened.

Optional query parameters:

| Parameter | Values |
| --- | --- |
| `journal_mode` | `wal` or `delete` |
| `busy_timeout` | non-negative milliseconds |

The engine default for `busy_timeout` is 60000. A new file starts in `delete` journal mode until `journal_mode` is set.

```java
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class Notes {
  public static void main(String[] args) throws Exception {
    Class.forName("com.myano.jsqlite.JDBC");
    String url = "jdbc:jsqlite:" + Path.of("notes.sqlite").toAbsolutePath()
        + "?journal_mode=wal&busy_timeout=8000";
    try (Connection connection = DriverManager.getConnection(url)) {
      try (PreparedStatement create = connection.prepareStatement(
          "CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT)")) {
        create.executeUpdate();
      }
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO notes (body) VALUES (?)")) {
        insert.setString(1, "hello");
        insert.executeUpdate();
      }
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT id, body FROM notes");
          ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          System.out.println(rows.getLong("id") + " " + rows.getString("body"));
        }
      }
    }
  }
}
```

`Connection.setAutoCommit(false)`, `commit()`, and `rollback()` are supported. Placeholders are `?`.

`jdbc:sqlite:` is accepted as well. Use `jdbc:jsqlite:` when another driver that also accepts `jdbc:sqlite:` is on the classpath, so `DriverManager` selects this driver.

## SQL and file format

Each `execute`, `executeQuery`, and `executeUpdate` takes one statement. An optional trailing semicolon is allowed; a second statement is rejected.

Parsed statements include `CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE`, `DROP`, `INSERT` (including `ON CONFLICT` / `INSERT OR IGNORE`), `UPDATE`, `DELETE`, and `SELECT` (`JOIN`, `WHERE`, `GROUP BY`, `ORDER BY`, `LIMIT` / `OFFSET`, `CASE`, `CAST`, `COALESCE`, aggregates such as `COUNT`, `SUM`, and `MAX`, and some scalar and `IN` subqueries).

Supported `PRAGMA` names include `journal_mode`, `busy_timeout`, `foreign_keys`, `table_info`, `freelist_count`, and `wal_checkpoint`. Names starting with `jsqlite_` are engine extensions (`jsqlite_analyze`, `jsqlite_ratio_index`, `jsqlite_sidecars`, `jsqlite_split_table`).

Some shapes fail with `SqlException`, including `ORDER BY` on aggregates and some subqueries that contain joins. Unknown pragmas and unknown URL properties fail the same way. JDBC methods the driver does not implement throw `SQLFeatureNotSupportedException`.

Existing files must be SQLite format 3, UTF-8. The file length must match the header page count. Databases this driver creates use 4096-byte pages. Opening a database that already has a `-wal` file the engine cannot apply fails and leaves the main file unchanged.

## Desktop browser

After `mvn package`:

```bash
java -jar dist/jsqlite.jar
```

That starts `com.myano.jsqlite.admin.DbAdmin`, a Swing window for opening a database, browsing schema, and running SQL. It needs a graphical environment.
