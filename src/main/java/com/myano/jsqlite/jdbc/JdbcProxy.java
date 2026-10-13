package com.myano.jsqlite.jdbc;

import com.myano.jsqlite.SqlException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

final class JdbcProxy {
  private JdbcProxy() {}

  @FunctionalInterface
  interface Dispatch {
    Object call(Method method, Object[] args) throws SQLException;
  }

  static <T> T create(Class<T> type, Dispatch dispatch) {
    InvocationHandler handler = (proxy, method, args) -> {
      if (method.getDeclaringClass() == Object.class) {
        return objectCall(proxy, method, args);
      }
      try {
        return dispatch.call(method, args == null ? new Object[0] : args);
      } catch (SqlException | IllegalStateException ex) {
        throw new SQLException(ex.getMessage(), ex);
      }
    };
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  static SQLException unsupported(Method method) {
    return new SQLFeatureNotSupportedException(
        method.getDeclaringClass().getSimpleName() + "." + method.getName());
  }

  private static Object objectCall(Object proxy, Method method, Object[] args) throws SQLException {
    return switch (method.getName()) {
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      case "toString" -> proxy.getClass().getName();
      default -> throw unsupported(method);
    };
  }
}
