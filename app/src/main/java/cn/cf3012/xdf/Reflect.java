package cn.cf3012.xdf;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

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
        return findFieldCached(clazz, name);
    }

    /* ==================== 查找缓存（2026-10-17 review：热路径去反射开销） ====================
     *
     * 每次 findMethod/findField 都沿继承链 getDeclaredXxx，在热路径上是纯浪费：
     * UiRestorer 的 Preference.onBindViewHolder（列表每次 bind）、InputMethodHooks
     * 每次 putString、UsbAuthHooks 的权限复核、AppConfig 反射读进程名等都会走到这里。
     * 用 ClassValue 做 per-Class 缓存（而不是全局 Map<类名,...>）：天然规避不同
     * ClassLoader 的同名类冲突、GC 友好、无锁读取。
     */

    private static final ClassValue<ConcurrentHashMap<String, Method>> METHOD_CACHE =
            new ClassValue<ConcurrentHashMap<String, Method>>() {
                @Override
                protected ConcurrentHashMap<String, Method> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private static final ClassValue<ConcurrentHashMap<String, Field>> FIELD_CACHE =
            new ClassValue<ConcurrentHashMap<String, Field>>() {
                @Override
                protected ConcurrentHashMap<String, Field> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    /** 查找缓存 key：方法名 + 形参类型名（null 参数类型记为 '?'） */
    private static String sig(String name, Class<?>[] types) {
        StringBuilder sb = new StringBuilder(name).append('(');
        if (types != null) {
            for (Class<?> t : types) {
                sb.append(t == null ? "?" : t.getName()).append(',');
            }
        }
        return sb.append(')').toString();
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] types)
            throws NoSuchMethodException {
        String key = sig(name, types);
        ConcurrentHashMap<String, Method> cache = METHOD_CACHE.get(clazz);
        Method cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method m = (types == null || types.length == 0)
                        ? c.getDeclaredMethod(name)
                        : c.getDeclaredMethod(name, types);
                cache.putIfAbsent(key, m);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "." + name);
    }

    /** 字段查找（含继承链）带缓存；findField 对外仍是唯一入口 */
    private static Field findFieldCached(Class<?> clazz, String name) throws NoSuchFieldException {
        ConcurrentHashMap<String, Field> cache = FIELD_CACHE.get(clazz);
        Field cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                cache.putIfAbsent(name, f);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "." + name);
    }
}
