package erdimport;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Small reflection helper. All Visual Paradigm API calls go through here, so the plugin does not
 * depend on the exact openapi.jar it was compiled against: methods are looked up by name and
 * argument types at run time inside Visual Paradigm.
 */
final class R {
  static ClassLoader loader = R.class.getClassLoader();

  private R() {}

  static Class<?> cls(String name) throws ClassNotFoundException {
    try {
      return Class.forName(name, true, loader);
    } catch (ClassNotFoundException e) {
      ClassLoader ctx = Thread.currentThread().getContextClassLoader();
      if (ctx != null && ctx != loader) return Class.forName(name, true, ctx);
      throw e;
    }
  }

  /** Calls a static method. */
  static Object st(String className, String method, Object... args) throws Exception {
    Class<?> c = cls(className);
    Method m = find(c, method, args, true);
    if (m == null) throw new NoSuchMethodException(className + "." + method + sig(args));
    return invoke(m, null, args);
  }

  /** Reads a public static field (constant). */
  static Object field(String className, String name) throws Exception {
    return cls(className).getField(name).get(null);
  }

  static Object fieldOr(String className, String name, Object fallback) {
    try { return field(className, name); } catch (Throwable t) { return fallback; }
  }

  /** Calls an instance method. */
  static Object call(Object target, String method, Object... args) throws Exception {
    if (target == null) throw new IllegalStateException("Nothing to call " + method + " on (null)");
    Method m = find(target.getClass(), method, args, false);
    if (m == null) throw new NoSuchMethodException(target.getClass().getName() + "." + method + sig(args));
    return invoke(m, target, args);
  }

  /** Calls an instance method and ignores any failure. Returns null on failure. */
  static Object tryCall(Object target, String method, Object... args) {
    try { return call(target, method, args); } catch (Throwable t) { return null; }
  }

  static boolean has(Object target, String method, int argCount) {
    if (target == null) return false;
    for (Method m : target.getClass().getMethods()) {
      if (m.getName().equals(method) && m.getParameterTypes().length == argCount) return true;
    }
    return false;
  }

  /** Builds a typed array (for example IDBColumn[]) from objects. */
  static Object typedArray(String componentClass, List<?> items) throws Exception {
    Class<?> c = cls(componentClass);
    Object arr = java.lang.reflect.Array.newInstance(c, items.size());
    for (int i = 0; i < items.size(); i++) java.lang.reflect.Array.set(arr, i, items.get(i));
    return arr;
  }

  private static Method find(Class<?> c, String name, Object[] args, boolean wantStatic) {
    for (Method m : c.getMethods()) {
      if (!m.getName().equals(name)) continue;
      if (m.getParameterTypes().length != args.length) continue;
      if (Modifier.isStatic(m.getModifiers()) != wantStatic) continue;
      if (!compatible(m.getParameterTypes(), args)) continue;
      return accessible(c, m);
    }
    return null;
  }

  private static boolean compatible(Class<?>[] params, Object[] args) {
    for (int i = 0; i < params.length; i++) {
      Object a = args[i];
      if (a == null) {
        if (params[i].isPrimitive()) return false;
      } else if (!wrap(params[i]).isInstance(a)) {
        return false;
      }
    }
    return true;
  }

  /** Implementation classes inside VP are often not public, so call through a public interface. */
  private static Method accessible(Class<?> c, Method m) {
    if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) return m;
    for (Class<?> t : supertypes(c)) {
      if (!Modifier.isPublic(t.getModifiers())) continue;
      try {
        return t.getMethod(m.getName(), m.getParameterTypes());
      } catch (NoSuchMethodException ignore) {
        // keep looking
      }
    }
    try { m.setAccessible(true); } catch (Throwable ignore) { /* best effort */ }
    return m;
  }

  private static List<Class<?>> supertypes(Class<?> c) {
    Set<Class<?>> out = new LinkedHashSet<Class<?>>();
    List<Class<?>> todo = new ArrayList<Class<?>>();
    todo.add(c);
    while (!todo.isEmpty()) {
      Class<?> k = todo.remove(0);
      if (k == null || !out.add(k)) continue;
      for (Class<?> i : k.getInterfaces()) todo.add(i);
      todo.add(k.getSuperclass());
    }
    return new ArrayList<Class<?>>(out);
  }

  private static Object invoke(Method m, Object target, Object[] args) throws Exception {
    try {
      return m.invoke(target, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new RuntimeException("Visual Paradigm reported an error in " + m.getName() + ": " + cause, cause);
    }
  }

  private static Class<?> wrap(Class<?> p) {
    if (!p.isPrimitive()) return p;
    if (p == boolean.class) return Boolean.class;
    if (p == int.class) return Integer.class;
    if (p == long.class) return Long.class;
    if (p == double.class) return Double.class;
    if (p == float.class) return Float.class;
    if (p == short.class) return Short.class;
    if (p == byte.class) return Byte.class;
    if (p == char.class) return Character.class;
    return Void.class;
  }

  private static String sig(Object[] args) {
    StringBuilder b = new StringBuilder("(");
    for (int i = 0; i < args.length; i++) {
      if (i > 0) b.append(", ");
      b.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
    }
    return b.append(")").toString();
  }
}
