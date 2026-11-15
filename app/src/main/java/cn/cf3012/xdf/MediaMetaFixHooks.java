package cn.cf3012.xdf;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MediaMetaFixHooks — 补全媒体库行的 {@code _size} / {@code width} / {@code height}。
 *
 * <p><b>为什么需要（2026-11-15 实机定位）</b></p>
 *
 * <p>本机 MediaProvider 是「sdcardfs + 无 pending 发布扫描」的裁剪版：</p>
 * <ul>
 *   <li>{@code mount | grep emulated} 全是 <b>sdcardfs</b>（不是 FUSE）→ 没有 AOSP Q
 *       FuseDaemon 的「写后自动索引」；</li>
 *   <li>SystemUI 截图走 {@code com.android.systemui.screenshot.SaveImageInBackgroundTask}
 *       的 pending API：{@code MediaStore.createPending()} 先插一条只有 _data/mime/时间的行，
 *       写完字节后 {@code PendingSession.publish()} 只做一次 {@code update(is_pending=0)}；</li>
 *   <li>本机 MediaProvider dex 里 <b>没有任何 "publish" 分支</b>（全文 0 命中），
 *       也没有 FUSE 扫描钩子 → 这条行永远停在 {@code _size = NULL}、{@code width/height = NULL}。</li>
 * </ul>
 *
 * <p>实测：{@code Pictures/Screenshots} 下 644 行里有 62 行 {@code _size IS NULL}，
 * <b>全部</b>是 {@code Screenshot_*}（新截图）；而 QQ 的相册过滤器
 * {@code MediaFileFilter.imageMinSize = 1000}（{@code PhotoCommonBaseData$MyFilterListener.filterMedia()}）
 * 把 {@code _size=NULL}（JDBC 读作 0）判成「0 字节图片」直接丢弃 →
 * <b>QQ 看不到刚截的图</b>，改个文件名（触发一次真实扫描）才出现。宽高全库皆 NULL 还让
 * QQ 每张图都要自己 {@code BitmapFactory.inJustDecodeBounds} 现算，拖慢相册首屏。</p>
 *
 * <p><b>修复策略（零额外写库、零权限问题的两条路）</b></p>
 * <ol>
 *   <li><b>热路径（新数据）</b>：hook {@code MediaProvider.update(Uri,ContentValues,String,String[])}
 *       <b>proceed 之前</b>，若这次 update 正是「发布」（{@code is_pending=0}）且 values 里没有
 *       {@code _size}，就用 {@code File.length()} + {@code inJustDecodeBounds} 算好
 *       <b>塞进同一个 ContentValues</b> —— Provider 自己把值写下去，不需要额外 update，
 *       不重入、不涉及跨 uid 权限。</li>
 *   <li><b>存量数据</b>：hook {@code MediaProvider.onCreate()}，延迟 8s 起后台线程扫
 *       {@code _size IS NULL OR width IS NULL}（图片）/ {@code _size IS NULL}（视频）逐行补全，
 *       单次上限与总时长都有硬闸，失败只记一次日志。uid 10043 持有
 *       {@code WRITE_MEDIA_STORAGE}，update 不会被 scoped storage 拦。</li>
 * </ol>
 *
 * <p><b>边界</b>：只改这三个列，不碰 {@code date_modified}/{@code date_added}（QQ 按
 * {@code date_modified DESC, date_added DESC} 排序，改时间会打乱相册顺序）；
 * 视频只补 {@code _size}（取宽高要 MediaMetadataRetriever，代价过高，且本机视频行本来就有尺寸）。
 * 一切异常 PROTECTIVE 吞掉，绝不影响 MediaProvider 正常读写。</p>
 *
 * <p>scope：{@code com.android.providers.media}（进程 {@code android.process.media}，
 * 不是 system_server）。改完 kill 该进程即生效 —— 系统会自动把它拉起来。</p>
 */
final class MediaMetaFixHooks {

    private static final String TAG = "MediaMetaFix";

    /** 媒体库表 uri（用字面量而不是框架常量，避免 ROM 差异） */
    private static final Uri IMAGES = Uri.parse("content://media/external/images/media");
    private static final Uri VIDEO = Uri.parse("content://media/external/video/media");

    private static final String COL_ID = "_id";
    private static final String COL_DATA = "_data";
    private static final String COL_SIZE = "_size";
    private static final String COL_WIDTH = "width";
    private static final String COL_HEIGHT = "height";
    private static final String COL_MIME = "mime_type";
    private static final String COL_PENDING = "is_pending";

    /** 存量补全：单表单次最多处理行数 */
    private static final int MAX_ROWS_PER_PASS = 2000;
    /** 存量补全：单表单次最长耗时（毫秒），超时留到下次进程启动 */
    private static final long BACKFILL_BUDGET_MS = 90_000L;
    /** 存量补全启动延迟：避开 Provider 自身初始化高峰 */
    private static final long BACKFILL_DELAY_MS = 8_000L;

    private static volatile Context sCtx;
    private static volatile Handler sBgHandler;
    private static final AtomicBoolean sBackfillStarted = new AtomicBoolean(false);
    private static final AtomicInteger sHotFixed = new AtomicInteger();
    private static final AtomicInteger sBackfillFixed = new AtomicInteger();
    private static volatile long sLastWarnAt = 0L;

    private MediaMetaFixHooks() {
    }

    /* ==================== 安装入口 ==================== */

    static void hookAll(ClassLoader cl) {
        Class<?> mp;
        try {
            mp = Class.forName("com.android.providers.media.MediaProvider", false, cl);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "MediaProvider class not found: " + t);
            return;
        }
        int ok = 0;
        ok += install("onCreate", () -> hookOnCreate(mp));
        ok += install("insert", () -> hookInsert(mp));
        ok += install("update", () -> hookUpdate(mp));
        XDFHook.logi(TAG, "installed " + ok + "/3 hooks in android.process.media");
        if (ok == 0) {
            XDFHook.logw(TAG, "no hook installed — 媒体库补全不会生效");
        }
    }

    private interface Installer {
        void run() throws Exception;
    }

    private static int install(String what, Installer body) {
        try {
            body.run();
            XDFHook.logi(TAG, "hooked: MediaProvider." + what);
            return 1;
        } catch (Throwable t) {
            XDFHook.logw(TAG, "hook MediaProvider." + what + " failed: " + t);
            return 0;
        }
    }

    /* ==================== hook 1：onCreate → 存量补全 ==================== */

    private static void hookOnCreate(Class<?> mp) throws Exception {
        Method m = Reflect.findDeclared(mp, "onCreate", new Class<?>[0]);
        m.setAccessible(true);
        XDFHook.deopt(m);
        XDFHook.hook(m, chain -> {
            Object ret = chain.proceed();
            try {
                captureContext(chain.getThisObject());
                scheduleBackfill();
            } catch (Throwable t) {
                warnThrottled("onCreate post: " + t);
            }
            return ret;
        });
    }

    /* ==================== hook 2：insert → 首次写入即补 ==================== */

    private static void hookInsert(Class<?> mp) throws Exception {
        Method m = Reflect.findDeclared(mp, "insert",
                new Class<?>[]{Uri.class, ContentValues.class});
        m.setAccessible(true);
        XDFHook.deopt(m);
        XDFHook.hook(m, chain -> {
            try {
                captureContext(chain.getThisObject());
                Uri uri = (Uri) chain.getArg(0);
                ContentValues cv = (ContentValues) chain.getArg(1);
                // insert 时文件往往还没写完（pending），只有「非 pending 且文件已存在」才补
                Integer pending = cv == null ? null : cv.getAsInteger(COL_PENDING);
                boolean allowWrite = (pending == null || pending == 0);
                if (allowWrite) {
                    fillValues(uri, cv, true, "insert");
                }
            } catch (Throwable t) {
                warnThrottled("insert fill: " + t);
            }
            Object ret = chain.proceed();
            // ★ 兜底：本机 MediaProvider 不认 pending 语义（没有 publish 分支），
            //   行可能「先建、后写文件」，写入瞬间补不了 → 延迟 1.2s / 4s 再补两次。
            try {
                if (ret instanceof Uri) {
                    scheduleComplete((Uri) ret, 0);
                }
            } catch (Throwable t) {
                warnThrottled("insert schedule: " + t);
            }
            return ret;
        });
    }

    /* ==================== 兜底：延迟补一次（insert 后文件才写完的场景） ==================== */

    private static void scheduleComplete(final Uri rowUri, final int attempt) {
        if (rowUri == null) {
            return;
        }
        String s = rowUri.toString();
        if (!s.startsWith("content://media/external/images/")
                && !s.startsWith("content://media/external/video/")) {
            return;
        }
        Handler h = ensureHandler();
        if (h == null) {
            return;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    completeRow(rowUri, attempt);
                } catch (Throwable t) {
                    warnThrottled("completeRow: " + t);
                }
            }
        }, attempt == 0 ? 1200L : 4000L);
    }

    /** 共享后台线程（补全任务都用它，避免每行一个线程） */
    private static synchronized Handler ensureHandler() {
        if (sBgHandler == null) {
            try {
                HandlerThread ht = new HandlerThread("XDFMediaMeta");
                ht.start();
                sBgHandler = new Handler(ht.getLooper());
            } catch (Throwable t) {
                XDFHook.logw(TAG, "ensureHandler failed: " + t);
                return null;
            }
        }
        return sBgHandler;
    }

    /** 回查单行：缺什么补什么；文件还没写完就再排一次（最多两轮） */
    private static void completeRow(Uri rowUri, int attempt) {
        Context ctx = sCtx;
        if (ctx == null) {
            return;
        }
        try {
            if (!AppConfig.get().hMediaMetaFix) {
                return;
            }
        } catch (Throwable ignored) {
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(rowUri,
                    new String[]{COL_DATA, COL_MIME, COL_SIZE, COL_WIDTH, COL_HEIGHT, COL_PENDING},
                    null, null, null);
            if (c == null || !c.moveToFirst()) {
                return;
            }
            String path = c.getString(0);
            String mime = c.getString(1);
            boolean hasSize = !c.isNull(2) && c.getLong(2) > 0;
            boolean hasDims = !c.isNull(3) && !c.isNull(4)
                    && c.getInt(3) > 0 && c.getInt(4) > 0;
            int pending = c.isNull(5) ? 0 : c.getInt(5);
            if (path == null || path.isEmpty()) {
                return;
            }
            File f = new File(path);
            if (!f.exists() || !f.isFile() || f.length() <= 0) {
                if (attempt < 2) {
                    scheduleComplete(rowUri, attempt + 1);
                }
                return;
            }
            if (pending != 0) {
                return;   // 仍是 pending：publish 那次 update 会补，不抢跑
            }
            ContentValues cv = new ContentValues(3);
            if (!hasSize) {
                cv.put(COL_SIZE, f.length());
            }
            if (!hasDims && isImage(mime, path)) {
                int[] wh = decodeBounds(path);
                if (wh != null && wh[0] > 0 && wh[1] > 0) {
                    cv.put(COL_WIDTH, wh[0]);
                    cv.put(COL_HEIGHT, wh[1]);
                }
            }
            if (cv.size() == 0) {
                return;
            }
            ctx.getContentResolver().update(rowUri, cv, null, null);
            int n = sHotFixed.incrementAndGet();
            XDFHook.logi(TAG, "completeRow(size=" + f.length() + ") attempt=" + attempt
                    + " file=" + f.getName() + " (total " + n + ")");
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /* ==================== hook 3：update → 发布那一刻补（核心） ==================== */

    private static void hookUpdate(Class<?> mp) throws Exception {
        Method m = Reflect.findDeclared(mp, "update",
                new Class<?>[]{Uri.class, ContentValues.class, String.class, String[].class});
        m.setAccessible(true);
        XDFHook.deopt(m);
        XDFHook.hook(m, chain -> {
            try {
                captureContext(chain.getThisObject());
                Uri uri = (Uri) chain.getArg(0);
                ContentValues cv = (ContentValues) chain.getArg(1);
                Integer pending = cv == null ? null : cv.getAsInteger(COL_PENDING);
                // ★ 只在「发布」这一刻动手：is_pending 由 1 置 0，文件此时已写完
                if (pending != null && pending == 0) {
                    fillValues(uri, cv, true, "publish");
                }
            } catch (Throwable t) {
                warnThrottled("update fill: " + t);
            }
            return chain.proceed();
        });
    }

    /* ==================== 核心：把 _size / 宽高塞进 ContentValues ==================== */

    /**
     * @param uri       目标行（发布时是单行 uri；insert 时是表 uri）
     * @param cv        即将交给 Provider 的 ContentValues（原地补写）
     * @param needDims  是否要算 width/height（发布/图片才需要）
     * @param where     日志标识
     */
    private static void fillValues(Uri uri, ContentValues cv, boolean needDims, String where) {
        if (uri == null || cv == null) {
            return;
        }
        // 运行时门控：开关「开→关」立即生效（不必重启进程）
        try {
            if (!AppConfig.get().hMediaMetaFix) {
                return;
            }
        } catch (Throwable ignored) {
        }
        if (cv.containsKey(COL_SIZE)) {
            return;   // 调用方自己带了，说明它已经算过（也可能是我们上一次补的）——不重复劳动
        }
        String path = cv.getAsString(COL_DATA);
        String mime = cv.getAsString(COL_MIME);
        if (path == null || path.isEmpty()) {
            // publish 的 update 只带 is_pending，路径得回查一次（hook 在 proceed 之前，
            // Provider 此时没持锁，query 安全）
            String[] row = queryRow(uri);
            if (row != null) {
                path = row[0];
                if (mime == null) {
                    mime = row[1];
                }
            }
        }
        if (path == null || path.isEmpty()) {
            return;
        }
        File f = new File(path);
        if (!f.exists() || !f.isFile()) {
            return;
        }
        long size = f.length();
        if (size <= 0L) {
            return;
        }
        cv.put(COL_SIZE, size);
        if (needDims && isImage(mime, path)) {
            int[] wh = decodeBounds(path);
            if (wh != null && wh[0] > 0 && wh[1] > 0) {
                cv.put(COL_WIDTH, wh[0]);
                cv.put(COL_HEIGHT, wh[1]);
            }
        }
        int n = sHotFixed.incrementAndGet();
        if (n <= 10 || n % 100 == 0) {
            XDFHook.logi(TAG, where + " filled size=" + size
                    + " dims=" + (needDims && isImage(mime, path)) + " file=" + f.getName()
                    + " (total " + n + ")");
        }
    }

    /** 按行 uri 回查 _data / mime_type；失败返回 null */
    private static String[] queryRow(Uri uri) {
        Context ctx = sCtx;
        if (ctx == null) {
            return null;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{COL_DATA, COL_MIME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                return new String[]{c.getString(0), c.getString(1)};
            }
        } catch (Throwable t) {
            warnThrottled("queryRow: " + t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    /* ==================== 存量补全 ==================== */

    private static void captureContext(Object providerObj) {
        if (sCtx != null) {
            return;
        }
        if (providerObj instanceof ContentProvider) {
            Context c = ((ContentProvider) providerObj).getContext();
            if (c != null) {
                sCtx = c.getApplicationContext();
            }
        }
    }

    private static void scheduleBackfill() {
        AppConfig cfg;
        try {
            cfg = AppConfig.refresh();
        } catch (Throwable t) {
            return;
        }
        if (!cfg.hMediaMetaBackfill) {
            XDFHook.logi(TAG, "backfill disabled by config, skip");
            return;
        }
        if (!sBackfillStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            Handler h = ensureHandler();
            if (h == null) {
                return;
            }
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        runBackfill();
                    } catch (Throwable t) {
                        XDFHook.logw(TAG, "backfill crashed: " + t);
                    }
                }
            }, BACKFILL_DELAY_MS);
            XDFHook.logi(TAG, "backfill scheduled in " + BACKFILL_DELAY_MS + "ms");
        } catch (Throwable t) {
            XDFHook.logw(TAG, "backfill schedule failed: " + t);
        }
    }

    private static void runBackfill() {
        Context ctx = sCtx;
        if (ctx == null) {
            XDFHook.logw(TAG, "backfill skipped: no context");
            return;
        }
        ContentResolver cr = ctx.getContentResolver();
        long t0 = SystemClock.elapsedRealtime();
        int images = backfillTable(cr, IMAGES, true);
        int videos = backfillTable(cr, VIDEO, false);
        XDFHook.logi(TAG, "backfill done: images=" + images + " videos=" + videos
                + " cost=" + (SystemClock.elapsedRealtime() - t0) + "ms"
                + " (total " + sBackfillFixed.get() + ")");
    }

    /**
     * 扫一张表并补全缺失列。
     *
     * @param wantDims true = 图片（补 _size + width/height），false = 视频（只补 _size）
     * @return 实际更新的行数
     */
    private static int backfillTable(ContentResolver cr, Uri base, boolean wantDims) {
        final String sel = wantDims
                ? "(" + COL_SIZE + " IS NULL OR " + COL_WIDTH + " IS NULL OR "
                        + COL_HEIGHT + " IS NULL OR " + COL_WIDTH + "<=0 OR " + COL_HEIGHT + "<=0)"
                : COL_SIZE + " IS NULL";
        int done = 0;
        Cursor c = null;
        long deadline = SystemClock.elapsedRealtime() + BACKFILL_BUDGET_MS;
        try {
            c = cr.query(base,
                    new String[]{COL_ID, COL_DATA, COL_MIME, COL_SIZE, COL_WIDTH, COL_HEIGHT},
                    sel, null, COL_ID + " DESC");
            if (c == null) {
                return 0;
            }
            while (done < MAX_ROWS_PER_PASS
                    && SystemClock.elapsedRealtime() < deadline
                    && c.moveToNext()) {
                long id = c.getLong(0);
                String path = c.getString(1);
                String mime = c.getString(2);
                boolean hasSize = !c.isNull(3) && c.getLong(3) > 0;
                boolean hasDims = !wantDims
                        || (!c.isNull(4) && !c.isNull(5) && c.getInt(4) > 0 && c.getInt(5) > 0);
                if (hasSize && hasDims) {
                    continue;
                }
                if (path == null || path.isEmpty()) {
                    continue;
                }
                File f = new File(path);
                if (!f.exists() || !f.isFile()) {
                    continue;   // 僵尸行：文件已删/已改名，不动它
                }
                ContentValues cv = new ContentValues(3);
                if (!hasSize) {
                    long size = f.length();
                    if (size > 0L) {
                        cv.put(COL_SIZE, size);
                    }
                }
                if (wantDims && !hasDims && isImage(mime, path)) {
                    int[] wh = decodeBounds(path);
                    if (wh != null && wh[0] > 0 && wh[1] > 0) {
                        cv.put(COL_WIDTH, wh[0]);
                        cv.put(COL_HEIGHT, wh[1]);
                    }
                }
                if (cv.size() == 0) {
                    continue;
                }
                try {
                    cr.update(ContentUris.withAppendedId(base, id), cv, null, null);
                    done++;
                    sBackfillFixed.incrementAndGet();
                } catch (Throwable t) {
                    warnThrottled("backfill update id=" + id + ": " + t);
                }
                if ((done % 50) == 0) {
                    try {
                        Thread.sleep(50L);   // 让出 IO，避免和相册滑动抢磁盘
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            warnThrottled("backfill query: " + t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return done;
    }

    /* ==================== 小工具 ==================== */

    private static boolean isImage(String mime, String path) {
        if (mime != null && mime.startsWith("image/")) {
            return true;
        }
        String p = path == null ? "" : path.toLowerCase();
        return p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg")
                || p.endsWith(".gif") || p.endsWith(".bmp") || p.endsWith(".webp")
                || p.endsWith(".heic") || p.endsWith(".heif");
    }

    /** 只读文件头拿宽高（inJustDecodeBounds，不分配像素内存） */
    private static int[] decodeBounds(String path) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opt);
            if (opt.outWidth > 0 && opt.outHeight > 0) {
                return new int[]{opt.outWidth, opt.outHeight};
            }
        } catch (Throwable t) {
            warnThrottled("decodeBounds: " + t);
        }
        return null;
    }

    /** 同一告警 60s 内只打一条，避免热路径刷日志 */
    private static void warnThrottled(String msg) {
        long now = SystemClock.elapsedRealtime();
        if (now - sLastWarnAt < 60_000L) {
            return;
        }
        sLastWarnAt = now;
        XDFHook.logw(TAG, msg);
    }
}
