package cn.cf3012.xdf;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 轻量反射工具：替代旧 API 的 XposedHelpers。
 * 沿继承链查找构造器/方法/字段，自动 setAccessible。
 * 所有查找都要求显式参数类型（XDF ROM 上的调试教训）。
 */
public final class Reflect {

    private Reflect() {
    }

    public static Class<?> findClass(String name, ClassLoader cl) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    public static Constructor<?> findConstructor(Class<?> clazz, Class<?>[] types)
            throws NoSuchMethodException {
        Constructor<?> c = clazz.getDeclaredConstructor(types);
        c.setAccessible(true);
        return c;
    }

    /** 显式指定参数类型构造（避免运行时类型精确匹配问题） */
    public static Object newInstance(Class<?> clazz, Class<?>[] types, Object... args)
            throws Exception {
        return findConstructor(clazz, types).newInstance(args);
    }

    /** 实例方法调用；types 为 null 或空数组表示无参 */
    public static Object call(Object obj, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method m = findMethod(obj.getClass(), name, types);
        m.setAccessible(true);
        return m.invoke(obj, args);
    }

    /** 静态方法调用 */
    public static Object callStatic(Class<?> clazz, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method m = findMethod(clazz, name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    public static Method findDeclared(Class<?> clazz, String name, Class<?>[] types)
            throws NoSuchMethodException {
        return findMethod(clazz, name, types);
    }

    /* ==================== 字段 ==================== */

    public static Object getField(Object obj, String name) throws Exception {
        Field f = findField(obj.getClass(), name);
        f.setAccessible(true);
        return f.get(obj);
    }

    public static void setField(Object obj, String name, Object value) throws Exception {
        Field f = findField(obj.getClass(), name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    public static Object getStaticField(Class<?> clazz, String name) throws Exception {
        Field f = findField(clazz, name);
        f.setAccessible(true);
        return f.get(null);
    }

    public static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "." + name);
    }

    /* ==================== 内部 ==================== */

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] types)
            throws NoSuchMethodException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return (types == null || types.length == 0)
                        ? c.getDeclaredMethod(name)
                        : c.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "." + name);
    }
}
