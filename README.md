# JSQLite

Pure-Java SQLite-compatible engine and JDBC driver (`com.myano.jsqlite.JDBC`, URL `jdbc:sqlite:`).

Used by [skoruba4j-is4-java](https://github.com/Myanowu/skoruba4j-is4-java) instead of `org.xerial:sqlite-jdbc`.

## Build

```bash
mvn test
mvn install
```

Artifact: `com.myano:jsqlite:0.1.0-SNAPSHOT`.

## With skoruba4j

Clone as a sibling of the skoruba4j tree, install, then build skoruba4j:

```bash
git clone https://github.com/Myanowu/jsqlite.git JSQLite
mvn -f JSQLite/pom.xml install
mvn -f skoruba4j-is4-java/pom.xml package
```
