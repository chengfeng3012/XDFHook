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

    /**
     * ★★ 不要用 {@code java.lang.ClassValue}！
     *
     * <p>踩坑记录（2026-11-13 真机）：{@code ClassValue} 在桌面 JDK 7+ 存在，
     * 但 <b>Android libcore 从未提供它</b>；本项目 compileSdk 37 的 android.jar 里能编过，
     * 而设备是 Android 10 (API 29) —— 运行时抛
     * {@code NoClassDefFoundError: Failed resolution of: Ljava/lang/ClassValue;}
     * → 本类 {@code <clinit>} 失败 → <b>整个 Reflect 不可用</b> →
     * 所有依赖它的 hook 全部 NoClassDefFoundError（实测 GalleryHooks 因此一个都没装上）。
     * 教训：新增任何 java.* API 必须对照 minSdk(26) 而不是 compileSdk。</p>
     *
     * <p>现用「外层 key = Class 对象」的两级 ConcurrentHashMap：Class 的 equals 是
     * identity 语义，天然隔离不同 ClassLoader 的同名类；Class 在进程内不会被 GC，
     * 不存在泄漏问题（hook 涉及的类数量有限）。</p>
     */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>>
            METHOD_CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>>
            FIELD_CACHE = new ConcurrentHashMap<>();

    private static ConcurrentHashMap<String, Method> methodCacheOf(Class<?> c) {
        ConcurrentHashMap<String, Method> m = METHOD_CACHE.get(c);
        if (m != null) {
            return m;
        }
        ConcurrentHashMap<String, Method> fresh = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Method> prev = METHOD_CACHE.putIfAbsent(c, fresh);
        return prev != null ? prev : fresh;
    }

    private static ConcurrentHashMap<String, Field> fieldCacheOf(Class<?> c) {
        ConcurrentHashMap<String, Field> m = FIELD_CACHE.get(c);
        if (m != null) {
            return m;
        }
        ConcurrentHashMap<String, Field> fresh = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Field> prev = FIELD_CACHE.putIfAbsent(c, fresh);
        return prev != null ? prev : fresh;
    }

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
        ConcurrentHashMap<String, Method> cache = methodCacheOf(clazz);
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
        ConcurrentHashMap<String, Field> cache = fieldCacheOf(clazz);
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
