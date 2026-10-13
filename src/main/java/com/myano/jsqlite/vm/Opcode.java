package com.myano.jsqlite.vm;

/**
 * 操作码名字对齐 SQLite 虚拟机，编号是声明顺序，不抄 sqlite 的数值。
 * 每条指令的操作数是 p1、p2、p3。
 */
public enum Opcode {
  /** p1 = 游标号，p2 = 常量池里的表名。 */
  OpenRead,
  /** p1 = 游标号，p2 = 常量池里的表名。 */
  OpenWrite,
  /** p1 = 游标；表空则跳到 p2。 */
  Rewind,
  /** 当前行的第 p2 列写入寄存器 p3。 */
  Column,
  /** 寄存器 p1 = p2。p2 是放得下 int 的有符号字面量。 */
  Integer,
  /** 寄存器 p1 = 常量池 p2 里的 Long。 */
  Int64,
  /** 寄存器 p1 = 常量池 p2 里的字符串。 */
  String,
  /** 寄存器 p1 = null。 */
  Null,
  /** 比较 r[p1] 与 r[p3]；条件成立则跳到 p2。任一为 null 则不跳。 */
  Eq,
  /** 比较 r[p1] 与 r[p3]；条件成立则跳到 p2。任一为 null 则不跳。 */
  Lt,
  /** 比较 r[p1] 与 r[p3]；条件成立则跳到 p2。任一为 null 则不跳。 */
  Gt,
  /** 跳到 p2。 */
  Goto,
  /** P1 不生成。留给整数主键。 */
  Rowid,
  /** 从寄存器 p1 起连续 p2 个值打成记录，放到 r[p3]。 */
  MakeRecord,
  /** 游标 p1 的表追加 r[p2] 这条记录。 */
  Insert,
  /** 从 r[p1] 起 p2 个寄存器作为一行输出。 */
  ResultRow,
  /** 游标 p1 前进；还有行则跳到 p2。 */
  Next,
  /** 停止。 */
  Halt,
  /** P1 内存表更新。p1 = 游标，p2 = 放着替换记录的寄存器。 */
  Update,
  /** P1 内存表删除。p1 = 游标，删掉当前行。 */
  Delete,
  /** 比较 r[p1] 与 r[p3]；条件成立则跳到 p2。任一为 null 则不跳。 */
  Le,
  /** 比较 r[p1] 与 r[p3]；条件成立则跳到 p2。任一为 null 则不跳。 */
  Ge,
  /** 比较 r[p1] 与 r[p3]；条件成立则跳到 p2。任一为 null 则不跳。 */
  Ne,
  /** 寄存器 p1 = 第 p2 个绑定值，下标从 0 起。 */
  Variable,
  /** r[p1] 是 null 则跳到 p2。 */
  IfNull,
  /** r[p1] 不是 null 则跳到 p2。 */
  NotNull,
  /** 文本 r[p1] 匹配模式 r[p3] 则跳到 p2。ASCII 不区分大小写。 */
  Like,
  /** r[p3] = r[p1] || r[p2]。任一侧是 null 则结果是 null。 */
  Concat,
  /** r[p3] = r[p1] 为 null 时取 r[p2]，否则取 r[p1]。 */
  IfNullValue,
  /** r[p1] = 当前 UTC 时间，格式 yyyy-MM-dd HH:mm:ss。 */
  Now,
  /** 游标 p1 按主键点查。键从 r[p3] 起。找不到跳到 p2。没有索引时扫描全表。 */
  SeekPk,
  /** r[p1] 加一。 */
  AggCount,
  /** 用 r[p2] 更新最大值 r[p1]。null 不参与。 */
  AggMax,
  /** 按程序里的排序键重排已经产出的行。 */
  Sort,
  /** 用程序里的 LIMIT / OFFSET 寄存器截断结果。 */
  Limit
}
