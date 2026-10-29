package com.myano.jsqlite.vm;

import com.myano.jsqlite.Row;
import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.store.Table;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class VirtualMachine {
  /** Nested CROSS JOIN without indexes can need tens of millions of steps on embed data. */
  private static final int STEP_LIMIT = 100_000_000;
  private static final int CURSOR_SLOTS = 32;
  private static final DateTimeFormatter UTC_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

  public List<Row> execute(Program program, MemoryCatalog catalog, Object[] bindings) {
    Object[] registers = new Object[program.registerCount()];
    Cursor[] cursors = new Cursor[CURSOR_SLOTS];
    List<Row> results = new ArrayList<>();
    List<KeyedRow> buffered = new ArrayList<>();
    Map<GroupKey, KeyedRow> grouped = new LinkedHashMap<>();
    GroupAgg agg = new GroupAgg();
    int pc = 0;
    for (int step = 0; step < STEP_LIMIT; step++) {
      if (pc < 0 || pc >= program.instructionCount()) {
        throw new SqlException("program counter out of range: " + pc);
      }
      Instruction instruction = program.instruction(pc);
      switch (instruction.op()) {
        case Halt -> {
          flushGroups(program, grouped, buffered, results);
          flush(program, buffered, results);
          return List.copyOf(results);
        }
        case Goto -> pc = instruction.p2();
        case OpenRead, OpenWrite -> {
          open(cursors, instruction, program, catalog);
          pc++;
        }
        case Rewind -> pc = rewind(cursors, instruction, pc);
        case Next -> pc = next(cursors, instruction, pc);
        case NextIdx -> pc = nextIdx(cursors, instruction, pc);
        case Column -> {
          column(cursors, instruction, registers);
          pc++;
        }
        case Integer -> {
          registers[instruction.p1()] = (long) instruction.p2();
          pc++;
        }
        case Int64 -> {
          registers[instruction.p1()] = longConstant(program, instruction.p2());
          pc++;
        }
        case String -> {
          registers[instruction.p1()] = stringConstant(program, instruction.p2());
          pc++;
        }
        case Null -> {
          registers[instruction.p1()] = null;
          pc++;
        }
        case Variable -> {
          registers[instruction.p1()] = binding(bindings, instruction.p2());
          pc++;
        }
        case Now -> {
          registers[instruction.p1()] = UTC_TIME.format(Instant.now());
          pc++;
        }
        case Eq, Lt, Gt, Le, Ge, Ne -> {
          if (matches(instruction.op(), registers[instruction.p1()], registers[instruction.p3()])) {
            pc = instruction.p2();
          } else {
            pc++;
          }
        }
        case Like -> {
          int pattern = instruction.p3() & 0xFFFF;
          char escape = (char) (instruction.p3() >>> 16);
          if (like(registers[instruction.p1()], registers[pattern], escape)) {
            pc = instruction.p2();
          } else {
            pc++;
          }
        }
        case IfNull -> pc = registers[instruction.p1()] == null ? instruction.p2() : pc + 1;
        case NotNull -> pc = registers[instruction.p1()] != null ? instruction.p2() : pc + 1;
        case Concat -> {
          registers[instruction.p3()] = concat(registers[instruction.p1()], registers[instruction.p2()]);
          pc++;
        }
        case IfNullValue -> {
          Object value = registers[instruction.p1()];
          registers[instruction.p3()] = value == null ? registers[instruction.p2()] : value;
          pc++;
        }
        case Cast -> {
          Object spec = program.constant(instruction.p3());
          if (!(spec instanceof CastSpec cast)) {
            throw new IllegalStateException("CAST type is missing");
          }
          registers[instruction.p1()] = castValue(registers[instruction.p2()], cast);
          pc++;
        }
        case Abs -> {
          registers[instruction.p1()] = absValue(registers[instruction.p2()]);
          pc++;
        }
        case GroupTemplate -> {
          agg.reset(registers, registerList(program, instruction.p2()));
          pc++;
        }
        case GroupLoad -> {
          agg.load(program, registers);
          pc++;
        }
        case GroupStore -> {
          agg.store(program, registers);
          pc++;
        }
        case GroupNext -> pc = agg.next(program, registers) ? pc + 1 : instruction.p2();
        case MakeRecord -> {
          registers[instruction.p3()] = makeRecord(registers, instruction);
          pc++;
        }
        case Insert -> pc = insert(cursors, instruction, registers, program, catalog, pc);
        case Update -> {
          update(cursors, instruction, registers, catalog);
          pc++;
        }
        case Delete -> {
          delete(cursors, instruction, catalog);
          pc++;
        }
        case SeekPk -> pc = seek(cursors, instruction, registers, pc);
        case SeekIdx -> pc = seekIdx(cursors, instruction, registers, program, pc);
        case AggCount -> {
          if (instruction.p3() == 0 || registers[instruction.p2()] != null) {
            Object current = registers[instruction.p1()];
            long count = current instanceof Long value ? value : 0L;
            registers[instruction.p1()] = count + 1;
          }
          pc++;
        }
        case AggMax -> {
          accumulateMax(registers, instruction);
          pc++;
        }
        case AggSum -> {
          accumulateSum(registers, instruction);
          pc++;
        }
        case AggCountDistinct -> {
          Object value = registers[instruction.p2()];
          if (value != null) {
            @SuppressWarnings("unchecked")
            Set<Object> seen = (Set<Object>) registers[instruction.p1()];
            if (seen == null) {
              seen = new HashSet<>();
              registers[instruction.p1()] = seen;
            }
            seen.add(distinctKey(value));
          }
          pc++;
        }
        case AggCard -> {
          Object current = registers[instruction.p1()];
          int size = current instanceof Set<?> seen ? seen.size() : 0;
          registers[instruction.p3()] = (long) size;
          pc++;
        }
        case Group -> {
          flushGroups(program, grouped, buffered, results);
          pc++;
        }
        case ResultRow -> {
          keep(program, registers, instruction, results, buffered, grouped);
          pc++;
        }
        case Sort -> {
          flush(program, buffered, results);
          pc++;
        }
        case Limit -> {
          applyLimit(program, registers, results);
          pc++;
        }
        case Rowid -> throw new SqlException("Rowid is not available until integer primary keys");
      }
    }
    throw new SqlException("execution step limit exceeded");
  }

  private void open(Cursor[] cursors, Instruction instruction, Program program, MemoryCatalog catalog) {
    int slot = instruction.p1();
    if (slot < 0 || slot >= cursors.length) {
      throw new SqlException("cursor " + slot + " is out of range");
    }
    Object constant = program.constant(instruction.p2());
    if (!(constant instanceof String name)) {
      throw new IllegalStateException("table name constant is not text");
    }
    Table table = catalog.find(name);
    if (table == null) {
      throw new SqlException("unknown table " + name);
    }
    cursors[slot] = new Cursor(table);
  }

  private int rewind(Cursor[] cursors, Instruction instruction, int pc) {
    Cursor cursor = requireCursor(cursors, instruction.p1());
    if (cursor.table.rowCount() == 0) {
      return instruction.p2();
    }
    cursor.index = 0;
    return pc + 1;
  }

  private int next(Cursor[] cursors, Instruction instruction, int pc) {
    Cursor cursor = requireCursor(cursors, instruction.p1());
    cursor.index++;
    if (cursor.index < cursor.table.rowCount()) {
      return instruction.p2();
    }
    return pc + 1;
  }

  private void column(Cursor[] cursors, Instruction instruction, Object[] registers) {
    Cursor cursor = requirePositioned(cursors, instruction.p1());
    registers[instruction.p3()] = cursor.table.value(cursor.index, instruction.p2());
  }

  private int seek(Cursor[] cursors, Instruction instruction, Object[] registers, int pc) {
    Cursor cursor = requireCursor(cursors, instruction.p1());
    cursor.hits = null;
    cursor.hitPos = 0;
    int length = cursor.table.primaryKey().length;
    Object[] key = new Object[length];
    for (int i = 0; i < length; i++) {
      key[i] = cell(registers[instruction.p3() + i]);
    }
    int found = cursor.table.seek(key);
    if (found < 0) {
      return instruction.p2();
    }
    cursor.index = found;
    return pc + 1;
  }

  private int seekIdx(
      Cursor[] cursors, Instruction instruction, Object[] registers, Program program, int pc) {
    Cursor cursor = requireCursor(cursors, instruction.p1());
    int[] columns = indexColumns(program, instruction.p4());
    Object[] key = new Object[columns.length];
    for (int i = 0; i < columns.length; i++) {
      key[i] = cell(registers[instruction.p3() + i]);
    }
    int[] hits = cursor.table.seekRows(columns, key);
    if (hits == null || hits.length == 0) {
      cursor.hits = null;
      cursor.hitPos = 0;
      cursor.index = -1;
      return instruction.p2();
    }
    cursor.hits = hits;
    cursor.hitPos = 0;
    cursor.index = hits[0];
    return pc + 1;
  }

  private int nextIdx(Cursor[] cursors, Instruction instruction, int pc) {
    Cursor cursor = requireCursor(cursors, instruction.p1());
    if (cursor.hits == null) {
      return pc + 1;
    }
    cursor.hitPos++;
    if (cursor.hitPos < cursor.hits.length) {
      cursor.index = cursor.hits[cursor.hitPos];
      return instruction.p2();
    }
    cursor.hits = null;
    cursor.hitPos = 0;
    cursor.index = -1;
    return pc + 1;
  }

  @SuppressWarnings("unchecked")
  private static int[] indexColumns(Program program, int constantIndex) {
    Object constant = program.constant(constantIndex);
    if (!(constant instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalStateException("SeekIdx needs column list constant");
    }
    int[] columns = new int[list.size()];
    for (int i = 0; i < list.size(); i++) {
      Object value = list.get(i);
      if (!(value instanceof Integer column)) {
        throw new IllegalStateException("SeekIdx column list must be integers");
      }
      columns[i] = column;
    }
    return columns;
  }

  private int insert(
      Cursor[] cursors,
      Instruction instruction,
      Object[] registers,
      Program program,
      MemoryCatalog catalog,
      int pc) {
    Cursor cursor = requireCursor(cursors, instruction.p1());
    Table.Place place = cursor.table.place(
        record(registers[instruction.p2()]), program.insertMode(), program.conflictColumns());
    return switch (place) {
      case INSERTED -> {
        catalog.addChange();
        Long id = cursor.table.identityKey(cursor.table.rowCount() - 1);
        if (id != null) {
          catalog.noteGeneratedKey(id);
        }
        catalog.checkInserted(cursor.table);
        yield program.insertMode() == InsertMode.UPSERT ? program.insertDone() : pc + 1;
      }
      case IGNORED -> pc + 1;
      case CONFLICT -> {
        cursor.index = cursor.table.takeConflict();
        yield pc + 1;
      }
    };
  }

  private void update(Cursor[] cursors, Instruction instruction, Object[] registers, MemoryCatalog catalog) {
    Cursor cursor = requirePositioned(cursors, instruction.p1());
    Object[] previous = cursor.table.copyRow(cursor.index);
    cursor.table.replace(cursor.index, record(registers[instruction.p2()]));
    catalog.addChange();
    catalog.checkReplaced(cursor.table, previous, cursor.table.copyRow(cursor.index));
  }

  private void delete(Cursor[] cursors, Instruction instruction, MemoryCatalog catalog) {
    Cursor cursor = requirePositioned(cursors, instruction.p1());
    Object[] previous = cursor.table.copyRow(cursor.index);
    cursor.table.remove(cursor.index);
    catalog.addChange();
    catalog.checkRemoved(cursor.table, previous);
    cursor.index--;
  }

  private void keep(
      Program program,
      Object[] registers,
      Instruction instruction,
      List<Row> results,
      List<KeyedRow> buffered,
      Map<GroupKey, KeyedRow> grouped) {
    Row row = resultRow(program, instruction, registers);
    int[] groups = program.groupRegisters();
    if (groups.length > 0) {
      Object[] parts = new Object[groups.length];
      for (int i = 0; i < parts.length; i++) {
        parts[i] = registers[groups[i]];
      }
      Object[] keys = new Object[program.orderKeys().size()];
      for (int i = 0; i < keys.length; i++) {
        keys[i] = registers[program.orderKeys().get(i).register()];
      }
      grouped.putIfAbsent(new GroupKey(parts), new KeyedRow(row, keys));
      return;
    }
    if (program.orderKeys().isEmpty()) {
      results.add(row);
      return;
    }
    Object[] keys = new Object[program.orderKeys().size()];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = registers[program.orderKeys().get(i).register()];
    }
    buffered.add(new KeyedRow(row, keys));
  }

  private void flushGroups(
      Program program,
      Map<GroupKey, KeyedRow> grouped,
      List<KeyedRow> buffered,
      List<Row> results) {
    if (grouped.isEmpty()) {
      return;
    }
    if (program.orderKeys().isEmpty()) {
      for (KeyedRow row : grouped.values()) {
        results.add(row.row);
      }
    } else {
      buffered.addAll(grouped.values());
    }
    grouped.clear();
  }

  private void flush(Program program, List<KeyedRow> buffered, List<Row> results) {
    if (buffered.isEmpty()) {
      return;
    }
    buffered.sort((left, right) -> {
      for (int i = 0; i < program.orderKeys().size(); i++) {
        int compared = compareValues(left.keys[i], right.keys[i]);
        if (program.orderKeys().get(i).descending()) {
          compared = -compared;
        }
        if (compared != 0) {
          return compared;
        }
      }
      return 0;
    });
    for (KeyedRow row : buffered) {
      results.add(row.row);
    }
    buffered.clear();
  }

  private void applyLimit(Program program, Object[] registers, List<Row> results) {
    int offset = program.offsetRegister() < 0 ? 0 : readCount(registers[program.offsetRegister()], "OFFSET");
    int limit = program.limitRegister() < 0
        ? results.size()
        : readCount(registers[program.limitRegister()], "LIMIT");
    if (offset > 0) {
      int drop = Math.min(offset, results.size());
      if (drop > 0) {
        results.subList(0, drop).clear();
      }
    }
    if (results.size() > limit) {
      results.subList(limit, results.size()).clear();
    }
  }

  private void accumulateMax(Object[] registers, Instruction instruction) {
    Object value = registers[instruction.p2()];
    if (value == null) {
      return;
    }
    Object current = registers[instruction.p1()];
    if (current == null || compare(Opcode.Lt, current, value)) {
      registers[instruction.p1()] = value;
    }
  }

  private void accumulateSum(Object[] registers, Instruction instruction) {
    Object value = registers[instruction.p2()];
    if (value == null) {
      return;
    }
    Double addend = number(value);
    if (addend == null) {
      return;
    }
    Object current = registers[instruction.p1()];
    if (current == null) {
      registers[instruction.p1()] = value instanceof Long ? value : addend;
      return;
    }
    if (current instanceof Long left && value instanceof Long right) {
      registers[instruction.p1()] = left + right;
      return;
    }
    Double base = number(current);
    if (base == null) {
      return;
    }
    registers[instruction.p1()] = base + addend;
  }

  private Row resultRow(Program program, Instruction instruction, Object[] registers) {
    int start = instruction.p1();
    int count = instruction.p2();
    if (count != program.resultColumns().size()) {
      throw new IllegalStateException("result width does not match column names");
    }
    List<Object> values = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      values.add(cell(registers[start + i]));
    }
    return new Row(program.resultColumns(), values);
  }

  private Object[] makeRecord(Object[] registers, Instruction instruction) {
    int start = instruction.p1();
    int count = instruction.p2();
    int dest = instruction.p3();
    if (dest >= start && dest < start + count) {
      throw new IllegalStateException("record destination overlaps its source");
    }
    Object[] record = new Object[count];
    System.arraycopy(registers, start, record, 0, count);
    return record;
  }

  private Object[] record(Object value) {
    if (value instanceof Object[] cells) {
      return cells;
    }
    throw new SqlException("expected a record");
  }

  private Object cell(Object value) {
    if (value == null || value instanceof Long || value instanceof String
        || value instanceof Double || value instanceof byte[]) {
      return value;
    }
    throw new SqlException("result value must be an integer, real, text, blob, or null");
  }

  private Object binding(Object[] bindings, int index) {
    if (bindings == null || index < 0 || index >= bindings.length) {
      throw new SqlException("binding index " + index + " is out of range");
    }
    return cell(bindings[index]);
  }

  private Object concat(Object left, Object right) {
    if (left == null || right == null) {
      return null;
    }
    return text(left) + text(right);
  }

  private static Object absValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean bit) {
      return bit ? 1L : 0L;
    }
    if (value instanceof Long number) {
      if (number == Long.MIN_VALUE) {
        throw new SqlException("ABS overflow");
      }
      return number < 0 ? -number : number;
    }
    if (value instanceof Double number) {
      return Math.abs(number);
    }
    if (value instanceof String text) {
      double number = Double.parseDouble(text.trim());
      if (number == Math.rint(number)) {
        return (long) Math.abs(number);
      }
      return Math.abs(number);
    }
    throw new SqlException("cannot take ABS of " + value);
  }

  private static int[] registerList(Program program, int constant) {
    Object value = program.constant(constant);
    if (!(value instanceof List<?> list)) {
      throw new IllegalStateException("group accumulator list is missing");
    }
    int[] registers = new int[list.size()];
    for (int i = 0; i < registers.length; i++) {
      registers[i] = (Integer) list.get(i);
    }
    return registers;
  }

  private static Object castValue(Object value, CastSpec spec) {
    if (value == null) {
      return null;
    }
    String type = spec.type().toLowerCase(Locale.ROOT);
    return switch (type) {
      case "bit" -> castBit(value);
      case "nvarchar", "varchar", "nchar", "char", "text", "ntext", "sysname" ->
          castText(value, spec.length());
      case "int", "integer", "bigint", "smallint", "tinyint" -> castInt(value);
      case "decimal", "numeric", "float", "real", "money", "smallmoney" ->
          castReal(value, spec.scale());
      case "datetime", "datetime2", "smalldatetime", "date", "time", "datetimeoffset" ->
          castText(value, -1);
      default -> throw new SqlException("unsupported CAST type " + spec.type());
    };
  }

  private static Object castBit(Object value) {
    if (value instanceof Boolean bit) {
      return bit ? 1L : 0L;
    }
    if (value instanceof Number number) {
      return number.doubleValue() == 0 ? 0L : 1L;
    }
    if (value instanceof String text) {
      String trimmed = text.trim();
      if (trimmed.equals("1") || trimmed.equalsIgnoreCase("true")) {
        return 1L;
      }
      if (trimmed.equals("0") || trimmed.equalsIgnoreCase("false")) {
        return 0L;
      }
    }
    throw new SqlException("cannot cast " + value + " to bit");
  }

  private static Object castText(Object value, int length) {
    String text;
    if (value instanceof String string) {
      text = string;
    } else if (value instanceof byte[] bytes) {
      text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    } else if (value instanceof Double number && number == Math.rint(number)) {
      text = Long.toString(number.longValue());
    } else {
      text = String.valueOf(value);
    }
    if (length >= 0 && text.length() > length) {
      return text.substring(0, length);
    }
    return text;
  }

  private static long castInt(Object value) {
    if (value instanceof Boolean bit) {
      return bit ? 1L : 0L;
    }
    if (value instanceof Number number) {
      return (long) number.doubleValue();
    }
    if (value instanceof String text) {
      String trimmed = text.trim();
      if (trimmed.isEmpty()) {
        throw new SqlException("cannot cast '' to int");
      }
      return (long) Double.parseDouble(trimmed);
    }
    throw new SqlException("cannot cast " + value + " to int");
  }

  private static Object castReal(Object value, int scale) {
    double number;
    if (value instanceof Boolean bit) {
      number = bit ? 1 : 0;
    } else if (value instanceof Number numeric) {
      number = numeric.doubleValue();
    } else if (value instanceof String text) {
      number = Double.parseDouble(text.trim());
    } else {
      throw new SqlException("cannot cast " + value + " to a number");
    }
    if (scale >= 0) {
      double factor = Math.pow(10, scale);
      number = Math.round(number * factor) / factor;
    }
    if (scale <= 0 && number == Math.rint(number)) {
      return (long) number;
    }
    return number;
  }

  private String text(Object value) {
    if (value instanceof String string) {
      return string;
    }
    if (value instanceof Long number) {
      return Long.toString(number);
    }
    if (value instanceof Double number) {
      return Double.toString(number);
    }
    throw new SqlException("|| needs integer or text");
  }

  private boolean matches(Opcode op, Object left, Object right) {
    return compare(op, left, right);
  }

  private boolean compare(Opcode op, Object left, Object right) {
    if (left == null || right == null) {
      return false;
    }
    int cmp = compareValues(left, right);
    if (!comparable(left, right)) {
      return false;
    }
    return switch (op) {
      case Eq -> cmp == 0;
      case Lt -> cmp < 0;
      case Gt -> cmp > 0;
      case Le -> cmp <= 0;
      case Ge -> cmp >= 0;
      case Ne -> cmp != 0;
      default -> throw new IllegalStateException(op.name());
    };
  }

  private int compareValues(Object left, Object right) {
    if (left == null && right == null) {
      return 0;
    }
    if (left == null) {
      return -1;
    }
    if (right == null) {
      return 1;
    }
    if (number(left) != null && number(right) != null) {
      return Double.compare(number(left), number(right));
    }
    if (left instanceof String leftText && right instanceof String rightText) {
      return leftText.compareTo(rightText);
    }
    if (left instanceof byte[] leftBytes && right instanceof byte[] rightBytes) {
      return java.util.Arrays.compareUnsigned(leftBytes, rightBytes);
    }
    if (left instanceof Long || left instanceof Double) {
      return -1;
    }
    if (right instanceof Long || right instanceof Double) {
      return 1;
    }
    return 0;
  }

  private static boolean comparable(Object left, Object right) {
    if (number(left) != null && number(right) != null) {
      return true;
    }
    if (left instanceof String && right instanceof String) {
      return true;
    }
    return left instanceof byte[] && right instanceof byte[];
  }

  private static Double number(Object value) {
    if (value instanceof Long number) {
      return number.doubleValue();
    }
    if (value instanceof Double number) {
      return number;
    }
    return null;
  }

  private boolean like(Object left, Object right, char escape) {
    if (!(left instanceof String text) || !(right instanceof String pattern)) {
      return false;
    }
    return likeAt(text, pattern, 0, 0, escape);
  }

  private boolean likeAt(String text, String pattern, int textIndex, int patternIndex, char escape) {
    while (patternIndex < pattern.length()) {
      char mark = pattern.charAt(patternIndex);
      if (escape != 0 && mark == escape) {
        if (patternIndex + 1 >= pattern.length()) {
          throw new SqlException("LIKE pattern ends with the escape character");
        }
        mark = pattern.charAt(++patternIndex);
        if (textIndex >= text.length() || fold(mark) != fold(text.charAt(textIndex))) {
          return false;
        }
        textIndex++;
        patternIndex++;
        continue;
      }
      if (mark == '%') {
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '%') {
          patternIndex++;
        }
        if (patternIndex == pattern.length()) {
          return true;
        }
        for (int i = textIndex; i <= text.length(); i++) {
          if (likeAt(text, pattern, i, patternIndex, escape)) {
            return true;
          }
        }
        return false;
      }
      if (textIndex >= text.length()) {
        return false;
      }
      if (mark != '_' && fold(mark) != fold(text.charAt(textIndex))) {
        return false;
      }
      textIndex++;
      patternIndex++;
    }
    return textIndex == text.length();
  }

  private char fold(char c) {
    if (c >= 'A' && c <= 'Z') {
      return (char) (c - 'A' + 'a');
    }
    return c;
  }

  private int readCount(Object value, String label) {
    if (!(value instanceof Long number) || number < 0 || number > Integer.MAX_VALUE) {
      throw new SqlException(label + " must be a non-negative integer");
    }
    return number.intValue();
  }

  private long longConstant(Program program, int index) {
    Object constant = program.constant(index);
    if (constant instanceof Long value) {
      return value;
    }
    throw new IllegalStateException("Int64 constant is not a long");
  }

  private String stringConstant(Program program, int index) {
    Object constant = program.constant(index);
    if (constant instanceof String value) {
      return value;
    }
    throw new IllegalStateException("String constant is not text");
  }

  private Cursor requireCursor(Cursor[] cursors, int slot) {
    if (slot < 0 || slot >= cursors.length || cursors[slot] == null) {
      throw new SqlException("cursor " + slot + " is not open");
    }
    return cursors[slot];
  }

  private Cursor requirePositioned(Cursor[] cursors, int slot) {
    Cursor cursor = requireCursor(cursors, slot);
    if (cursor.index < 0 || cursor.index >= cursor.table.rowCount()) {
      throw new SqlException("cursor " + slot + " is not positioned on a row");
    }
    return cursor;
  }

  private static final class Cursor {
    private final Table table;
    private int index = -1;
    private int[] hits;
    private int hitPos;

    private Cursor(Table table) {
      this.table = table;
    }
  }

  private record KeyedRow(Row row, Object[] keys) {}

  private static final class GroupAgg {
    private int[] registers = new int[0];
    private Object[] template = new Object[0];
    private final Map<GroupKey, Object[]> saved = new LinkedHashMap<>();
    private Iterator<Map.Entry<GroupKey, Object[]>> cursor;
    private boolean started;

    private void reset(Object[] live, int[] aggregateRegisters) {
      registers = aggregateRegisters;
      template = copy(live, aggregateRegisters);
      saved.clear();
      cursor = null;
      started = false;
    }

    private void load(Program program, Object[] live) {
      Object[] values = saved.get(key(program, live));
      paste(live, registers, values == null ? template : values);
    }

    private void store(Program program, Object[] live) {
      saved.put(key(program, live), copy(live, registers));
    }

    private boolean next(Program program, Object[] live) {
      if (!started) {
        cursor = saved.entrySet().iterator();
        started = true;
      }
      if (cursor == null || !cursor.hasNext()) {
        return false;
      }
      Map.Entry<GroupKey, Object[]> entry = cursor.next();
      int[] groups = program.groupRegisters();
      for (int i = 0; i < groups.length; i++) {
        live[groups[i]] = entry.getKey().parts[i];
      }
      paste(live, registers, entry.getValue());
      return true;
    }

    private static GroupKey key(Program program, Object[] live) {
      int[] groups = program.groupRegisters();
      Object[] parts = new Object[groups.length];
      for (int i = 0; i < parts.length; i++) {
        parts[i] = live[groups[i]];
      }
      return new GroupKey(parts);
    }

    private static Object[] copy(Object[] live, int[] indexes) {
      Object[] values = new Object[indexes.length];
      for (int i = 0; i < indexes.length; i++) {
        values[i] = live[indexes[i]];
      }
      return values;
    }

    private static void paste(Object[] live, int[] indexes, Object[] values) {
      for (int i = 0; i < indexes.length; i++) {
        live[indexes[i]] = values[i];
      }
    }
  }

  private static final class GroupKey {
    private final Object[] parts;

    private GroupKey(Object[] parts) {
      this.parts = parts;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof GroupKey key && Arrays.deepEquals(parts, key.parts);
    }

    @Override
    public int hashCode() {
      return Arrays.deepHashCode(parts);
    }
  }

  private static Object distinctKey(Object value) {
    if (value instanceof byte[] bytes) {
      return new BytesKey(bytes);
    }
    return value;
  }

  private static final class BytesKey {
    private final byte[] bytes;

    private BytesKey(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof BytesKey key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(bytes);
    }
  }
}
